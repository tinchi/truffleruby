package org.truffleruby.core.array.layout;

import java.util.HashMap;
import java.util.Map.Entry;

import org.truffleruby.Layouts;
import org.truffleruby.core.array.ConcurrentArray.FastLayoutLockArray;
import org.truffleruby.core.array.ConcurrentArray.LayoutLockArray;
import org.truffleruby.core.hash.ConcurrentHash;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;

public class ThreadWithDirtyFlag extends Thread {

    private final ThreadStateProvider threadStateProvider = new ThreadStateProvider();

    private final HashMap<FastLayoutLock, ThreadStateReference> lockStates = new HashMap<>();
    private DynamicObject lastFLLObject = null;
    private ThreadStateReference last = null;

    private final HashMap<Object, LayoutLock.Accessor> lockAccessors = new HashMap<>();
    private DynamicObject lastLLObject = null;
    private LayoutLock.Accessor lastAccessor = null;

    public ThreadWithDirtyFlag(Runnable runnable) {
        super(runnable);
    }

    public ThreadStateReference getThreadState(DynamicObject array, ConditionProfile fastPathProfile) {
        if (fastPathProfile.profile(lastFLLObject == array)) {
            return last;
        }
        return getThreadStateSlowPath(array);
    }

    @TruffleBoundary
    private ThreadStateReference getThreadStateSlowPath(DynamicObject array) {
        final FastLayoutLockArray fastLayoutLockArray = (FastLayoutLockArray) Layouts.ARRAY.getStore(array);
        final FastLayoutLock lock = fastLayoutLockArray.getLock();
        ThreadStateReference ts = lockStates.get(lock);
        if (ts == null) {
            ts = threadStateProvider.newThreadStateReference();
            lock.registerThread(ts);
            lockStates.put(lock, ts);
        }
        lastFLLObject = array;
        last = ts;
        return ts;
    }

    public LayoutLock.Accessor getLayoutLockAccessor(DynamicObject object, ConditionProfile fastPathProfile) {
        if (fastPathProfile.profile(lastLLObject == object)) {
            return lastAccessor;
        }
        return getLayoutLockAccessorSlowPath(object);
    }

    @TruffleBoundary
    private LayoutLock.Accessor getLayoutLockAccessorSlowPath(DynamicObject object) {
        LayoutLock.Accessor accessor = lockAccessors.get(object);
        if (accessor == null) {
            if (Layouts.ARRAY.isArray(object)) {
                accessor = ((LayoutLockArray) Layouts.ARRAY.getStore(object)).getLock().access();
            } else {
                accessor = ConcurrentHash.getStore(object).getLayoutLock().access();
            }
            lockAccessors.put(object, accessor);
        }
        lastLLObject = object;
        lastAccessor = accessor;
        return accessor;
    }

    public void cleanup() {
        for (Entry<FastLayoutLock, ThreadStateReference> entry : lockStates.entrySet()) {
            entry.getKey().unregisterThread(entry.getValue());
        }
    }

}
