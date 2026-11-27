#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L
#include <dirent.h>
#include <pthread.h>
#include <stdatomic.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <sys/inotify.h>
#include <time.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Standalone PIE in nativeLibraryDir. No shell, find extensions or per-file processes. */
struct pending {
    struct pending *next;
    struct map_tile *tile;
    char path[];
};

struct map_tile {
    struct map_tile *next;
    struct stat info;
    _Atomic int64_t bytes;
    char path[];
};

static struct map_tile *tiles;
static char **excluded;
static int excluded_count;
static atomic_int errors;
static int shallow;
static int follow_root;
static int map_mode;
static int hide_dot;
static int packed;
static int root_anchor = -1;
static int direct_directory_from(int base, const char *path);
static int direct_directory(const char *path) { return direct_directory_from(-1, path); }
static _Thread_local unsigned char records[16 * 1024];
static _Thread_local size_t record_bytes;

static int flush_records(void) {
    size_t length = record_bytes;
    record_bytes = 0;
    return length && fwrite(records, 1, length, stdout) != length ? -1 : 0;
}

static void put_le(unsigned char *out, uint64_t value, unsigned width) {
    for (unsigned i = 0; i < width; i++, value >>= 8) out[i] = (unsigned char) value;
}

static int64_t size_sum(int64_t a, int64_t b) {
    return INT64_MAX - a < b ? INT64_MAX : a + b;
}

static void add_tile_bytes(struct map_tile *tile, int64_t bytes) {
    if (!bytes) return;
    int64_t old = atomic_load_explicit(&tile->bytes, memory_order_relaxed);
    while (!atomic_compare_exchange_weak_explicit(&tile->bytes, &old,
            size_sum(old, bytes), memory_order_relaxed, memory_order_relaxed)) {}
}

static void failure(void) {
    fprintf(stderr, "root-index: %s\n", strerror(errno));
    errors = 1;
}

static int compare_paths(const void *a, const void *b) {
    return strcmp(*(char *const *)a, *(char *const *)b);
}

static int excluded_path(const char *path, int top) {
    /* Descendants can only be reached if all their ancestors passed this check. */
    if (!top) return excluded_count && bsearch(&path, excluded, excluded_count,
            sizeof(*excluded), compare_paths) != NULL;
    for (int i = 0; i < excluded_count; ++i) {
        size_t length = strlen(excluded[i]);
        if (!strncmp(path, excluded[i], length)
                && (!path[length] || path[length] == '/')) return 1;
    }
    return 0;
}

static int emit_record(const char *path, int directory, int64_t bytes, int64_t modified) {
    if (packed) {
        // One type byte, two signed LE 64-bit fields, a LE 32-bit length and raw path bytes.
        size_t path_len = strlen(path), length = 21 + path_len;
        if (record_bytes + length > sizeof(records) && flush_records() < 0) return -1;
        unsigned char *dest = records + record_bytes;
        dest[0] = directory ? 'd' : '-';
        put_le(dest + 1, (uint64_t) bytes, 8);
        put_le(dest + 9, (uint64_t) modified, 8);
        put_le(dest + 17, path_len, 4);
        memcpy(dest + 21, path, path_len);
        record_bytes += length;
        return 0;
    }
    return fprintf(stdout, "%s%c%" PRId64 "%c%" PRId64 "%c%s%c",
            directory ? "d---------" : "----------", 0,
            bytes, 0, modified, 0, path, 0) < 0 ? -1 : 0;
}
static int emit(const char *path, const struct stat *info, int64_t bytes) {
    return emit_record(path, S_ISDIR(info->st_mode), bytes,
            (int64_t) info->st_mtim.tv_sec * 1000 + info->st_mtim.tv_nsec / 1000000);
}

/* A queued directory carries its immediate-child tile through the whole traversal. */
static int visit(const char *path, int parent_fd, const char *name, int top, struct map_tile *tile, struct pending **pending, int64_t *local_bytes) {
    if (excluded_path(path, top)) return 0;
    struct stat info;
    if (top && root_anchor >= 0 ? fstat(root_anchor, &info) : fstatat(parent_fd, name, &info, AT_SYMLINK_NOFOLLOW)) {
        failure(); return 0;
    }
    if (root_anchor < 0 && (shallow || (follow_root && top)) && S_ISLNK(info.st_mode)) {
        // Links to directories are navigable only when explicitly opened.
        if (stat(path, &info) || !S_ISDIR(info.st_mode)) return 0;
    }
    int directory = S_ISDIR(info.st_mode);
    if (follow_root && top && !directory) { errno = ENOTDIR; failure(); return 0; }
    if (!directory && !S_ISREG(info.st_mode)) return 0;
    if (map_mode && !top) {
        if (!tile && !directory) return emit(path, &info, info.st_size);
        if (!tile) {
            size_t length = strlen(path) + 1;
            tile = malloc(sizeof(*tile) + length);
            if (!tile) { failure(); return -1; }
            tile->next = tiles;
            tile->info = info;
            atomic_init(&tile->bytes, 0);
            memcpy(tile->path, path, length);
            tiles = tile;
        }
        if (!directory && info.st_size > 0) *local_bytes = size_sum(*local_bytes, info.st_size);
    } else if (emit(path, &info, directory ? INT64_C(0) : info.st_size) < 0) {
        return -1;
    }
    if (!directory || (shallow && !top)) return 0;
    size_t length = strlen(path);
    struct pending *item = malloc(sizeof(*item) + length + 1);
    if (!item) { failure(); return -1; }
    item->tile = tile;
    memcpy(item->path, path, length + 1);
    item->next = *pending;
    *pending = item;
    return 0;
}

static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready=PTHREAD_COND_INITIALIZER;
static int active,stop;
static struct pending *jobs;
static const char *root_path;
static void *walk_worker(void *unused){
    (void)unused;
    struct pending *pending = NULL;
    int working = 0;
    int fatal = 0;
    for (;;) {
        pthread_mutex_lock(&lock);
        if (pending) {
            struct pending *tail = pending;
            while (tail->next) tail = tail->next;
            tail->next = jobs;
            jobs = pending;
            pending = NULL;
            pthread_cond_broadcast(&ready);
        }
        if (working) active--;
        if (fatal) stop = 1;
        while (!stop && !jobs && active) pthread_cond_wait(&ready, &lock);
        if (stop || !jobs) {
            pthread_cond_broadcast(&ready);
            pthread_mutex_unlock(&lock);
            break;
        }
        struct pending *item = jobs;
        jobs = item->next;
        active++;
        working = 1;
        pthread_mutex_unlock(&lock);
        int flags = O_RDONLY | O_DIRECTORY | O_CLOEXEC;
        if (!follow_root || strcmp(item->path, root_path)) flags |= O_NOFOLLOW;
        int fd;
        if (root_anchor >= 0) {
            size_t root_length = strlen(root_path);
            const char *relative = !strcmp(item->path, root_path) ? "" : item->path + root_length + 1;
            int pin = direct_directory_from(root_anchor, relative);
            fd = pin < 0 ? -1 : openat(pin, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
            if (pin >= 0) { int saved = errno; close(pin); errno = saved; }
        } else fd = open(item->path, flags);
        DIR *directory = fd < 0 ? NULL : fdopendir(fd);
        if (!directory && fd >= 0) { int saved = errno; close(fd); errno = saved; }
        if (!directory) {
            failure();
            if (follow_root && !strcmp(item->path, root_path)) fatal = 1;
            free(item);
            continue;
        }
        size_t length = strlen(item->path);
        char path[PATH_MAX];
        memcpy(path, item->path, length);
        if (length != 1 || path[0] != '/') path[length++] = '/';
        int64_t local_bytes = 0;
        for (;;) {
            errno = 0;
            struct dirent *child = readdir(directory);
            if (!child) { if (errno) failure(); break; }
            if (!strcmp(child->d_name, ".") || !strcmp(child->d_name, "..")) continue;
            if (hide_dot && child->d_name[0] == '.') continue;
            size_t name_length = strlen(child->d_name);
            if (length + name_length >= sizeof(path)) { errno = ENAMETOOLONG; failure(); continue; }
            memcpy(path + length, child->d_name, name_length + 1);
            if (visit(path, fd, child->d_name, 0, item->tile, &pending, &local_bytes) < 0) { fatal = 1; break; }
        }
        if (item->tile) add_tile_bytes(item->tile, local_bytes);
        if (closedir(directory)) failure();
        free(item);
    }
    if (flush_records() < 0) {
        pthread_mutex_lock(&lock);
        stop = 1;
        pthread_cond_broadcast(&ready);
        pthread_mutex_unlock(&lock);
    }
    return NULL;
}

/* Foreground-only event hint. No recursive listing, file reads, or persistent daemon. */
#define LIVE_WATCH_LIMIT 16
#define LIVE_EVENT_LIMIT 32
#ifndef LIVE_WAIT_MS
#define LIVE_WAIT_MS 30000
#endif

static int64_t monotonic_ms(void) {
    struct timespec now;
    return clock_gettime(CLOCK_MONOTONIC, &now) ? -1
            : (int64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

/* Open the actual directory inode, refusing links in every path component. */
static int direct_directory_from(int base, const char *path) {
    if ((base < 0 && (path[0] != '/' || !path[1])) || (base >= 0 && path[0] == '/') || strlen(path) >= PATH_MAX) {
        errno = EINVAL;
        return -1;
    }
    int fd = base < 0 ? open("/", O_PATH | O_DIRECTORY | O_CLOEXEC) : fcntl(base, F_DUPFD_CLOEXEC, 0);
    for (const char *part = path + (base < 0); fd >= 0 && *part;) {
        const char *end = strchr(part, '/');
        size_t length = end ? (size_t) (end - part) : strlen(part);
        char name[NAME_MAX + 1];
        if (!length || length > NAME_MAX || (end && !end[1])
                || (length == 1 && part[0] == '.')
                || (length == 2 && part[0] == '.' && part[1] == '.')) {
            close(fd);
            errno = EINVAL;
            return -1;
        }
        memcpy(name, part, length);
        name[length] = 0;
        int child = openat(fd, name, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        int saved = errno;
        close(fd);
        errno = saved;
        fd = child;
        if (!end) break;
        part = end + 1;
    }
    return fd;
}

static int live_watch(const char *scope, int count, char **paths) {
    if (!packed || count < 0 || count >= LIVE_WATCH_LIMIT || !strcmp(scope, "/")) return 2;
    int in = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (in < 0) { failure(); return 2; }
    int descriptors[LIVE_WATCH_LIMIT];
    const char *watched[LIVE_WATCH_LIMIT];
    int registered = 0;
    size_t scope_len = strlen(scope);
    struct stat watched_scope = {0};
    int watch_anchor = -1;
    uint32_t mask = IN_CREATE | IN_DELETE | IN_MOVED_FROM | IN_MOVED_TO
            | IN_CLOSE_WRITE | IN_ATTRIB | IN_DELETE_SELF | IN_MOVE_SELF | IN_ONLYDIR;
    for (int i = 0; i <= count; ++i) {
        const char *path = i ? paths[i - 1] : scope;
        if (i && (strncmp(path, scope, scope_len) || path[scope_len] != '/')) continue;
        int dir = i ? direct_directory_from(watch_anchor, path + scope_len + 1) : direct_directory(path);
        int watch = -1;
        if (dir >= 0) {
            if (!i && fstat(dir, &watched_scope)) { close(dir); close(in); return 2; }
            char link[48];
            snprintf(link, sizeof(link), "/proc/self/fd/%d", dir);
            // /proc/self/fd anchors the watch to the opened inode even during concurrent renames.
            watch = inotify_add_watch(in, link, mask);
            int saved = errno;
            if (!i && watch >= 0) watch_anchor = dir;
            else close(dir);
            errno = saved;
        }
        if (watch < 0) {
            if (!i) { failure(); close(in); return 2; }
            continue;
        }
        int duplicate = 0;
        for (int n = 0; n < registered; ++n) if (descriptors[n] == watch) duplicate = 1;
        if (!duplicate) {
            descriptors[registered] = watch;
            watched[registered++] = path;
        }
    }
    close(watch_anchor);
    struct pollfd queue = { .fd = in, .events = POLLIN };
    int64_t start = monotonic_ms(), deadline = start + LIVE_WAIT_MS;
    if (start < 0) { close(in); return 2; }
    int sent = 0, gap = 0, result = 0;
    while (sent < LIVE_EVENT_LIMIT) {
        int64_t now = monotonic_ms();
        if (now < 0 || now >= deadline) break;
        int won = poll(&queue, 1, (int) (deadline - now));
        if (!won) break;
        if (won < 0) {
            if (errno == EINTR) continue;
            failure(); result = 2; break;
        }
        if (!(queue.revents & POLLIN)) { result = 2; break; }
        union { struct inotify_event align; char bytes[8192]; } buffer;
        ssize_t length = read(in, buffer.bytes, sizeof(buffer.bytes));
        if (length < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            failure(); result = 2; break;
        }
        if (!length) break;
        int64_t received = monotonic_ms();
        if (!sent && received >= 0 && received + 125 < deadline) deadline = received + 125; // short burst coalescing
        for (size_t offset = 0; offset + sizeof(struct inotify_event) <= (size_t) length;) {
            struct inotify_event *event = (struct inotify_event *) (buffer.bytes + offset);
            if (event->len > (size_t) length - offset - sizeof(*event)) { gap = 1; break; }
            offset += sizeof(*event) + event->len;
            if (event->mask & (IN_Q_OVERFLOW | IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED)) { gap = 1; continue; }
            if (!event->len || !memchr(event->name, 0, event->len)) continue;
            if ((event->mask & IN_ISDIR) && !(event->mask & (IN_CREATE | IN_DELETE | IN_MOVED_FROM | IN_MOVED_TO))) continue;
            const char *base = NULL;
            for (int n = 0; n < registered; ++n) if (descriptors[n] == event->wd) base = watched[n];
            if (!base || !event->name[0] || strchr(event->name, '/')) continue;
            char child[PATH_MAX];
            int count = snprintf(child, sizeof(child), "%s/%s", base, event->name);
            if (count < 0 || (size_t) count >= sizeof(child)) { gap = 1; continue; }
            if (sent >= LIVE_EVENT_LIMIT) { gap = 1; continue; }
            struct stat hint = {0};
            hint.st_mode = (event->mask & IN_ISDIR) ? S_IFDIR : S_IFREG;
            if (emit(child, &hint, (int64_t) event->mask) < 0) { result = 2; break; }
            ++sent;
        }
        if (result) break;
    }
    close(in);
    if (sent || gap) {
        // Identity accompanies only dirty sessions, not idle timeouts. Overload
        // the packed metadata payload with device/inode for this scope record.
        if (emit_record(scope, 1, (int64_t) watched_scope.st_dev, (int64_t) watched_scope.st_ino) < 0) result = 2;
    }
    if (gap || sent == LIVE_EVENT_LIMIT) {
        struct stat hint = {0};
        hint.st_mode = S_IFDIR;
        if (emit(scope, &hint, -1) < 0) result = 2;
    }
    if (flush_records() < 0 || fflush(stdout)) result = 2;
    return result;
}

int main(int argc, char **argv) {
    if (argc < 2 || argv[1][0] != '/' || strlen(argv[1]) >= PATH_MAX) {
        fputs("root-index: invalid traversal root\n", stderr);
        return 2;
    }
    int arg = 2;
    if (arg < argc && !strcmp(argv[arg], "--packed")) { packed = 1; arg++; }
    if (arg < argc && !strcmp(argv[arg], "--watch")) return live_watch(argv[1], argc - arg - 1, argv + arg + 1);
    const char *mode = arg < argc ? argv[arg] : "";
    int strict = !strcmp(mode, "--children-pinned") || !strcmp(mode, "--map-pinned") || !strcmp(mode, "--map-visible-pinned");
    shallow = !strcmp(mode, "--children") || !strcmp(mode, "--children-pinned");
    int scoped_tree = !strcmp(mode, "--tree");
    map_mode = !strcmp(mode, "--map") || !strcmp(mode, "--map-visible")
            || !strcmp(mode, "--map-pinned") || !strcmp(mode, "--map-visible-pinned");
    hide_dot = map_mode && (!strcmp(mode, "--map-visible") || !strcmp(mode, "--map-visible-pinned"));
    follow_root = shallow || scoped_tree || map_mode;
    if (follow_root) arg++;
    uintmax_t expected_device = 0, expected_inode = 0;
    if (strict) {
        if (arg + 1 >= argc) return 2;
        char *end;
        errno = 0;
        expected_device = strtoumax(argv[arg++], &end, 10);
        if (errno || *end) return 2;
        expected_inode = strtoumax(argv[arg++], &end, 10);
        if (errno || *end || !expected_inode) return 2;
        root_anchor = direct_directory(argv[1]);
        struct stat pinned;
        if (root_anchor < 0 || fstat(root_anchor, &pinned) ||
                pinned.st_dev != expected_device || pinned.st_ino != expected_inode) {
            if (root_anchor >= 0) close(root_anchor);
            fputs("root-index: selected directory identity changed\n", stderr);
            return 2;
        }
    }
    excluded = argv + arg;
    excluded_count = argc - arg;
    qsort(excluded, excluded_count, sizeof(*excluded), compare_paths);
    static char output[64 * 1024];
    setvbuf(stdout, output, _IOFBF, sizeof(output));
    struct pending *pending = NULL;
    int64_t unused_bytes = 0;
    int fatal = visit(argv[1], AT_FDCWD, argv[1], 1, NULL, &pending, &unused_bytes) < 0;
    root_path = argv[1];
    jobs = pending;
    pthread_t workers[3];
    int count = 0;
    if ((!follow_root || map_mode) && jobs) {
        while (count < 3 && pthread_create(&workers[count], NULL, walk_worker, NULL) == 0) count++;
    }
    walk_worker(NULL);
    for (int i = 0; i < count; i++) pthread_join(workers[i], NULL);
    pending = jobs;
    fatal |= stop;
    while (pending) { struct pending *item = pending; pending = item->next; free(item); }
    while (tiles) {
        struct map_tile *tile = tiles;
        tiles = tile->next;
        if (!fatal && emit(tile->path, &tile->info, atomic_load_explicit(&tile->bytes, memory_order_relaxed)) < 0) fatal = 1;
        free(tile);
    }
    if (flush_records() < 0 || fflush(stdout)) { failure(); fatal = 1; }
    if (root_anchor >= 0) {
        int current = direct_directory(argv[1]);
        struct stat info;
        if (current < 0 || fstat(current, &info) || info.st_dev != expected_device || info.st_ino != expected_inode) fatal = 1;
        if (current >= 0) close(current);
        close(root_anchor);
    }
    return fatal ? 2 : errors;
}
