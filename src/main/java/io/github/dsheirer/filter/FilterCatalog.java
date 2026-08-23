/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Immutable browser-facing description of a filter tree.
 *
 * <p>Keys are opaque stable identifiers.  The signature changes whenever the ordered tree, its labels, or its
 * timeslot choices change, allowing a browser to replace one filter selection model atomically.</p>
 */
public record FilterCatalog(String signature, List<Node> groups, List<Integer> timeslots)
{
    public FilterCatalog
    {
        signature = Objects.requireNonNull(signature, "signature cannot be null");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups cannot be null"));
        timeslots = List.copyOf(Objects.requireNonNull(timeslots, "timeslots cannot be null"));
    }

    /** Builds a catalog with a compact deterministic signature over its complete ordered contents. */
    public static FilterCatalog create(List<Node> groups, List<Integer> timeslots)
    {
        List<Node> immutableGroups = List.copyOf(Objects.requireNonNull(groups, "groups cannot be null"));
        List<Integer> immutableTimeslots = List.copyOf(Objects.requireNonNull(timeslots,
            "timeslots cannot be null"));
        return new FilterCatalog(signature(immutableGroups, immutableTimeslots), immutableGroups, immutableTimeslots);
    }

    private static String signature(List<Node> groups, List<Integer> timeslots)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "filter-catalog-v1");

            for(Node group: groups)
            {
                update(digest, group);
            }

            digest.update((byte)0x7F);

            for(Integer timeslot: timeslots)
            {
                update(digest, String.valueOf(timeslot));
            }

            byte[] compact = Arrays.copyOf(digest.digest(), 12);
            return "v1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(compact);
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, Node node)
    {
        digest.update((byte)0x01);
        update(digest, node.key());
        update(digest, node.label());

        for(Node child: node.children())
        {
            update(digest, child);
        }

        digest.update((byte)0x02);
    }

    private static void update(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte)(bytes.length >>> 24));
        digest.update((byte)(bytes.length >>> 16));
        digest.update((byte)(bytes.length >>> 8));
        digest.update((byte)bytes.length);
        digest.update(bytes);
    }

    /** A group or leaf.  Leaves always carry an empty children list. */
    public record Node(String key, String label, List<Node> children)
    {
        public Node
        {
            key = Objects.requireNonNull(key, "key cannot be null");
            label = Objects.requireNonNull(label, "label cannot be null");
            children = List.copyOf(Objects.requireNonNull(children, "children cannot be null"));
        }
    }
}
