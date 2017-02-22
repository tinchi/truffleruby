package org.truffleruby.core.array.layout;

import java.util.concurrent.atomic.AtomicInteger;

import org.truffleruby.core.array.layout.FastLayoutLockNodesFactory.FastLayoutLockFinishLayoutChangeNodeGen;
import org.truffleruby.core.array.layout.FastLayoutLockNodesFactory.FastLayoutLockStartLayoutChangeNodeGen;
import org.truffleruby.core.array.layout.FastLayoutLockNodesFactory.FastLayoutLockStartWriteNodeGen;
import org.truffleruby.language.RubyBaseNode;
import org.truffleruby.language.RubyNode;

import com.oracle.truffle.api.dsl.Specialization;

public abstract class FastLayoutLockNodes {

    public static abstract class FastLayoutLockStartWriteNode extends RubyBaseNode {
        public static FastLayoutLockStartWriteNode create() {
            return FastLayoutLockStartWriteNodeGen.create();
        }

        public abstract void executeStartWrite(AtomicInteger threadState);

        @Specialization
        protected void fastLayoutLockStartWrite(AtomicInteger threadState) {
            FastLayoutLock.GLOBAL_LOCK.startWrite(threadState);
        }
    }

    public static abstract class FastLayoutLockStartLayoutChangeNode extends RubyNode {
        public static FastLayoutLockStartLayoutChangeNode create() {
            return FastLayoutLockStartLayoutChangeNodeGen.create();
        }

        public abstract long executeStartLayoutChange();

        @Specialization
        protected long startLayoutChange() {
            return FastLayoutLock.GLOBAL_LOCK.startLayoutChange();
        }
    }

    public static abstract class FastLayoutLockFinishLayoutChangeNode extends RubyBaseNode {
        public static FastLayoutLockFinishLayoutChangeNode create() {
            return FastLayoutLockFinishLayoutChangeNodeGen.create();
        }

        public abstract void executeFinishLayoutChange(long stamp);

        @Specialization
        protected void finishLayoutChange(long stamp) {
            FastLayoutLock.GLOBAL_LOCK.finishLayoutChange(stamp);
        }
    }
}
