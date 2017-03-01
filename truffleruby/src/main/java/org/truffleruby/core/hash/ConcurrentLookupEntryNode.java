/*
 * Copyright (c) 2014, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.hash;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;

import org.truffleruby.Layouts;
import org.truffleruby.core.array.layout.GetLayoutLockAccessorNode;
import org.truffleruby.core.array.layout.LayoutLock;
import org.truffleruby.language.RubyBaseNode;

public class ConcurrentLookupEntryNode extends RubyBaseNode {

    @Child HashNode hashNode = new HashNode();
    @Child CompareHashKeysNode compareHashKeysNode = new CompareHashKeysNode();

    @Child GetLayoutLockAccessorNode getAccessorNode = GetLayoutLockAccessorNode.create();

    private final ConditionProfile byIdentityProfile = ConditionProfile.createBinaryProfile();
    private final ConditionProfile dirtyProfile = ConditionProfile.createBinaryProfile();
    private final ConditionProfile isRemovedProfile = ConditionProfile.createBinaryProfile();

    public ConcurrentHashLookupResult lookup(VirtualFrame frame, DynamicObject hash, Object key) {
        final LayoutLock.Accessor accessor = getAccessorNode.executeGetAccessor(hash);
        ConcurrentHashLookupResult result;

        do {
            result = doLookup(frame, hash, key);
        } while (!accessor.finishRead(dirtyProfile));

        return result;
    }

    private ConcurrentHashLookupResult doLookup(VirtualFrame frame, DynamicObject hash, Object key) {
        final boolean compareByIdentity = byIdentityProfile.profile(Layouts.HASH.getCompareByIdentity(hash));
        int hashed = hashNode.hash(frame, key, compareByIdentity);

        final AtomicReferenceArray<ConcurrentEntry> entries = ConcurrentHash.getStore(hash).getBuckets();
        final int index = BucketsStrategy.getBucketIndex(hashed, entries.length());
        final ConcurrentEntry firstEntry = entries.get(index);

        ConcurrentEntry entry = firstEntry;
        ConcurrentEntry previousEntry = null;

        while (entry != null) {
            if (!isRemovedProfile.profile(entry.isRemoved()) &&
                    equalKeys(frame, compareByIdentity, key, hashed, entry.getKey(), entry.getHashed())) {
                return new ConcurrentHashLookupResult(entries, hashed, index, previousEntry, entry);
            }

            previousEntry = entry;
            entry = entry.getNextInLookup();
        }

        return new ConcurrentHashLookupResult(entries, hashed, index, firstEntry, null);
    }

    protected boolean equalKeys(VirtualFrame frame, boolean compareByIdentity, Object key, int hashed, Object otherKey, int otherHashed) {
        return compareHashKeysNode.equalKeys(frame, compareByIdentity, key, hashed, otherKey, otherHashed);
    }

}
