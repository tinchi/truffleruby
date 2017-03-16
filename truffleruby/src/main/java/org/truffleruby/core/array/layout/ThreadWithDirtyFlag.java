package org.truffleruby.core.array.layout;

import java.util.HashMap;
import java.util.Map.Entry;

import org.truffleruby.Layouts;
import org.truffleruby.core.array.ConcurrentArray.FastLayoutLockArray;
import org.truffleruby.core.array.ConcurrentArray.LayoutLockArray;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;

public class ThreadWithDirtyFlag extends Thread {

    private final ThreadStateProvider threadStateProvider = new ThreadStateProvider();

    private final HashMap<DynamicObject, ThreadStateReference> lockStates = new HashMap<>();
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
        ThreadStateReference ts = lockStates.get(array);
        if (ts == null) {
            FastLayoutLockArray fastLayoutLockArray = (FastLayoutLockArray) Layouts.ARRAY.getStore(array);
            FastLayoutLock lock = fastLayoutLockArray.getLock();
            ts = threadStateProvider.newThreadStateReference();
            lock.registerThread(ts);
            lockStates.put(array, ts);
        }
        lastFLLObject = array;
        last = ts;
        return ts;
    }

    public LayoutLock.Accessor getLayoutLockAccessor(DynamicObject array, ConditionProfile fastPathProfile) {
        if (fastPathProfile.profile(lastLLObject == array)) {
            return lastAccessor;
        }
        return getLayoutLockAccessorSlowPath(array);
    }

    @TruffleBoundary
    private LayoutLock.Accessor getLayoutLockAccessorSlowPath(DynamicObject array) {
        System.err.println("slow path");
        LayoutLock.Accessor accessor = lockAccessors.get(array);
        if (accessor == null) {
            accessor = ((LayoutLockArray) Layouts.ARRAY.getStore(array)).getLock().access();
            lockAccessors.put(array, accessor);
        }
        lastLLObject = array;
        lastAccessor = accessor;
        return accessor;
    }

    public void cleanup() {
        for (Entry<DynamicObject, ThreadStateReference> entry : lockStates.entrySet()) {
            FastLayoutLock lock = ((FastLayoutLockArray) Layouts.ARRAY.getStore(entry.getKey())).getLock();
            lock.unregisterThread(entry.getValue());
        }
    }

}
