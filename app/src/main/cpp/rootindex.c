#define _POSIX_C_SOURCE 200809L
#include <dirent.h>
#include <pthread.h>
#include <stdatomic.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
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
    int64_t bytes;
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

static int emit(const char *path, const struct stat *info, int64_t bytes) {
    int64_t modified = (int64_t) info->st_mtim.tv_sec * 1000 + info->st_mtim.tv_nsec / 1000000;
    return fprintf(stdout, "%s%c%" PRId64 "%c%" PRId64 "%c%s%c",
            S_ISDIR(info->st_mode) ? "d---------" : "----------", 0,
            bytes, 0, modified, 0, path, 0) < 0 ? -1 : 0;
}

/* A queued directory carries its immediate-child tile through the whole traversal. */
static int visit(const char *path, int parent_fd, const char *name, int top, struct map_tile *tile, struct pending **pending) {
    if (excluded_path(path, top)) return 0;
    struct stat info;
    if (fstatat(parent_fd, name, &info, AT_SYMLINK_NOFOLLOW)) { failure(); return 0; }
    if ((shallow || (follow_root && top)) && S_ISLNK(info.st_mode)) {
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
            tile->bytes = 0;
            memcpy(tile->path, path, length);
            tiles = tile;
        }
        if (!directory && info.st_size > 0) tile->bytes += info.st_size;
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
        int fd = open(item->path, flags);
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
        for (;;) {
            errno = 0;
            struct dirent *child = readdir(directory);
            if (!child) { if (errno) failure(); break; }
            if (!strcmp(child->d_name, ".") || !strcmp(child->d_name, "..")) continue;
            if (hide_dot && child->d_name[0] == '.') continue;
            size_t name_length = strlen(child->d_name);
            if (length + name_length >= sizeof(path)) { errno = ENAMETOOLONG; failure(); continue; }
            memcpy(path + length, child->d_name, name_length + 1);
            if (visit(path, fd, child->d_name, 0, item->tile, &pending) < 0) { fatal = 1; break; }
        }
        if (closedir(directory)) failure();
        free(item);
    }
    return NULL;
}

int main(int argc, char **argv) {
    if (argc < 2 || argv[1][0] != '/' || strlen(argv[1]) >= PATH_MAX) {
        fputs("root-index: invalid traversal root\n", stderr);
        return 2;
    }
    shallow = argc == 3 && !strcmp(argv[2], "--children");
    int scoped_tree = argc >= 3 && !strcmp(argv[2], "--tree");
    map_mode = argc >= 3 && (!strcmp(argv[2], "--map") || !strcmp(argv[2], "--map-visible"));
    hide_dot = map_mode && !strcmp(argv[2], "--map-visible");
    follow_root = shallow || scoped_tree || map_mode;
    excluded = argv + (follow_root ? 3 : 2);
    excluded_count = argc - (follow_root ? 3 : 2);
    qsort(excluded, excluded_count, sizeof(*excluded), compare_paths);
    static char output[64 * 1024];
    setvbuf(stdout, output, _IOFBF, sizeof(output));
    struct pending *pending = NULL;
    int fatal = visit(argv[1], AT_FDCWD, argv[1], 1, NULL, &pending) < 0;
    root_path = argv[1];
    jobs = pending;
    pthread_t workers[3];
    int count = 0;
    if (!follow_root && jobs) {
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
        if (!fatal && emit(tile->path, &tile->info, tile->bytes) < 0) fatal = 1;
        free(tile);
    }
    if (fflush(stdout)) { failure(); fatal = 1; }
    return fatal ? 2 : errors;
}
