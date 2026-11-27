package dev.fastsearch.android;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sparse, directory-only projection. Expanding a branch never replaces its siblings. */
final class FolderTree {
    private static final int MAX_DEPTH = 18;
    static final int MAX_ROWS = 1600;

    static final class Node {
        final String path, name;
        final int depth;
        final Map<String, Node> children = new LinkedHashMap<>();
        boolean expanded, loading, loaded, failed;

        Node(String path, String name, int depth) {
            this.path = path; this.name = name; this.depth = depth;
        }

        boolean canExpand() {
            return depth < MAX_DEPTH && (!loaded || !children.isEmpty());
        }

        void read(List<String> directories) {
            Map<String, Node> incoming = new LinkedHashMap<>();
            for (String folder : directories) {
                if (!path.equals(new File(folder).getParent())) continue;
                Node existing = children.get(folder);
                incoming.put(folder, existing != null ? existing
                        : new Node(folder, new File(folder).getName(), depth + 1));
            }
            children.clear(); children.putAll(incoming);
            loaded = true; loading = failed = false;
        }
    }

    static final class Row {
        final Node node;
        final int depth;
        final String status;

        Row(Node node, int depth, String status) {
            this.node = node; this.depth = depth; this.status = status;
        }
    }

    final List<Node> roots = new ArrayList<>();

    FolderTree(Map<String, String> locations) {
        for (Map.Entry<String, String> location : locations.entrySet()) {
            roots.add(new Node(location.getKey(), location.getValue(), 0));
        }
    }

    List<Node> discloseCurrent(String current) {
        // Find the deepest location prefix (a mounted volume may also appear under Device root).
        Node nearest = null;
        for (Node root : roots) {
            String prefix = root.path.equals("/") ? "/" : root.path + "/";
            if ((current.equals(root.path) || current.startsWith(prefix))
                    && (nearest == null || root.path.length() > nearest.path.length())) nearest = root;
        }
        ArrayList<String> parts = new ArrayList<>();
        if (nearest != null) {
            for (String parent = current; parent != null && !parent.equals(nearest.path); parent = new File(parent).getParent()) {
                parts.add(parent);
            }
        }
        if (nearest == null || parts.size() > MAX_DEPTH) {
            nearest = new Node(current, "Current location", 0);
            roots.add(0, nearest);
            parts.clear();
        }
        ArrayList<Node> path = new ArrayList<>();
        Node node = nearest;
        path.add(node);
        for (int i = parts.size() - 1; i >= 0; i--) {
            String part = parts.get(i);
            Node child = new Node(part, new File(part).getName(), node.depth + 1);
            node.children.put(part, child);
            node = child;
            path.add(node);
        }
        for (Node visible : path) visible.expanded = visible.depth < MAX_DEPTH;
        return path;
    }

    List<Row> rows() {
        ArrayList<Row> rows = new ArrayList<>();
        for (Node root : roots) append(rows, root);
        return rows;
    }

    private void append(List<Row> rows, Node node) {
        if (rows.size() >= MAX_ROWS) return;
        rows.add(new Row(node, node.depth, ""));
        if (!node.expanded) return;
        for (Node child : node.children.values()) {
            if (rows.size() >= MAX_ROWS) break;
            append(rows, child);
        }
        if (rows.size() >= MAX_ROWS) return;
        if (node.loading) rows.add(new Row(null, node.depth + 1, "Reading folders…"));
        else if (node.failed) rows.add(new Row(null, node.depth + 1, "Could not read. Collapse and expand to retry."));
        else if (node.loaded && node.children.isEmpty()) rows.add(new Row(null, node.depth + 1, "No subfolders"));
    }
}
