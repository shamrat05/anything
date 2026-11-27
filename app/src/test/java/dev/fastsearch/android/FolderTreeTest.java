package dev.fastsearch.android;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Host probe for the same rows shown by the shared folder chooser. */
public final class FolderTreeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("anything-folder-tree").toAbsolutePath();
        try {
            Path current = Files.createDirectories(fixture.resolve("Projects/Reports/Sent"));
            Path sibling = Files.createDirectories(fixture.resolve("Projects/Receipts"));
            Path distant = Files.createDirectories(fixture.resolve("Archive/Tax/Forms"));
            Files.createFile(fixture.resolve("not-a-folder.txt"));
            Map<String, String> roots = new LinkedHashMap<>();
            roots.put(fixture.toString(), "Test files");
            FolderTree tree = new FolderTree(roots);
            List<FolderTree.Node> open = tree.discloseCurrent(current.toString());
            check(open.size() == 4, "current folder and three ancestors are visible");
            for (FolderTree.Node node : open) read(node);
            check(row(tree, current) != null, "current folder stays visible after reads");
            check(row(tree, fixture.resolve("not-a-folder.txt")) == null, "file is never shown as a folder");
            check(row(tree, sibling) != null, "sibling in an open ancestor is one tap away");

            FolderTree.Node archive = row(tree, fixture.resolve("Archive"));
            check(archive != null && !archive.loaded && row(tree, distant) == null, "other branch is not eagerly scanned");
            int distantTaps = 1;
            archive.expanded = true; read(archive); distantTaps++;
            FolderTree.Node tax = row(tree, fixture.resolve("Archive/Tax"));
            check(tax != null && row(tree, current) != null, "expanding another branch keeps current branch");
            tax.expanded = true; read(tax); distantTaps++;
            check(row(tree, distant) != null, "second expansion exposes distant folder");
            check(distantTaps == 3, "two expansions plus one direct choice in the tree");
            check(oldTaps(current, sibling, fixture.resolve("Projects")) == 4, "old sibling list needed four taps");
            check(oldTaps(current, distant, fixture) == 7, "old distant list needed seven taps");

            archive.expanded = false;
            check(row(tree, distant) == null && row(tree, sibling) != null, "collapse hides only the branch");
            archive.expanded = true;
            check(row(tree, distant) != null, "re-expand reuses the cached branch");

            roots.put(fixture.resolve("Projects").toString(), "Projects root");
            FolderTree split = new FolderTree(roots);
            check(split.discloseCurrent(current.toString()).get(0).path.equals(fixture.resolve("Projects").toString()),
                    "uses deepest offered location as the current branch");
            FolderTree.Node receiver = new FolderTree.Node(fixture.resolve("Archive").toString(), "Archive", 0);
            List<String> supplied = new ArrayList<>();
            supplied.add(fixture.resolve("Projects").toString());
            supplied.add(fixture.resolve("Archive/Tax").toString());
            supplied.add(distant.toString());
            receiver.read(supplied);
            check(receiver.children.size() == 1, "accepts immediate children only");
            System.out.println("PASS " + checks + " folder tree checks (sibling 4 → 1 taps; distant 7 → 3 taps in fixture).");
        } finally {
            try (Stream<Path> files = Files.walk(fixture)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
            }
        }
    }

    private static int oldTaps(Path current, Path target, Path ancestor) {
        return ancestor.relativize(current).getNameCount() + ancestor.relativize(target).getNameCount() + 1;
    }

    private static void read(FolderTree.Node node) throws Exception {
        try (Stream<Path> children = Files.list(java.nio.file.Paths.get(node.path))) {
            node.read(children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted().map(Path::toString).collect(Collectors.toList()));
        }
    }

    private static FolderTree.Node row(FolderTree model, Path wanted) {
        for (FolderTree.Row row : model.rows()) if (row.node != null && row.node.path.equals(wanted.toString())) return row.node;
        return null;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
