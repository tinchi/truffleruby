/*
 * Copyright (c) 2013, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.debug;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.TruffleObject;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.Log;
import org.truffleruby.RubyLanguage;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreMethodNode;
import org.truffleruby.core.array.ArrayStrategy;
import org.truffleruby.core.binding.BindingNodes;
import org.truffleruby.core.cast.NameToJavaStringNode;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.LazyRubyNode;
import org.truffleruby.language.backtrace.Backtrace;
import org.truffleruby.language.backtrace.BacktraceFormatter;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.objects.shared.SharedObjects;
import org.truffleruby.language.yield.YieldNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.GraphPrintVisitor;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;

@CoreClass("Truffle::Debug")
public abstract class TruffleDebugNodes {

    @CoreMethod(names = "break_handle", onSingleton = true, required = 2, needsBlock = true, lowerFixnum = 2)
    public abstract static class BreakNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(file)")
        public DynamicObject setBreak(DynamicObject file, int line, final DynamicObject block) {
            final String fileString = StringOperations.decodeUTF8(file);

            final SourceSectionFilter filter = SourceSectionFilter.newBuilder()
                    .mimeTypeIs(RubyLanguage.MIME_TYPE)
                    .sourceIs(source -> source != null && source.getPath() != null && source.getPath().equals(fileString))
                    .lineIs(line)
                    .tagIs(StandardTags.StatementTag.class)
                    .build();

            final EventBinding<?> breakpoint = getContext().getInstrumenter().attachFactory(filter,
                    eventContext -> new ExecutionEventNode() {

                        @Child private YieldNode yieldNode = new YieldNode();

                        @Override
                        protected void onEnter(VirtualFrame frame) {
                            yieldNode.dispatch(frame, block, BindingNodes.createBinding(getContext(), frame.materialize()));
                        }

                    });

            return Layouts.HANDLE.createHandle(coreLibrary().getHandleFactory(), breakpoint);
        }

    }

    @CoreMethod(names = "remove_handle", onSingleton = true, required = 1)
    public abstract static class RemoveNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isHandle(handle)")
        public DynamicObject remove(DynamicObject handle) {
            EventBinding.class.cast(Layouts.HANDLE.getObject(handle)).dispose();
            return nil();
        }

    }

    @CoreMethod(names = "java_class_of", onSingleton = true, required = 1)
    public abstract static class JavaClassOfNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject javaClassOf(Object value) {
            return createString(StringOperations.encodeRope(value.getClass().getSimpleName(), UTF8Encoding.INSTANCE));
        }

    }

    @CoreMethod(names = "java_to_string", onSingleton = true, required = 1)
    public abstract static class JavaToStringNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject javaToString(Object value) {
            return createString(StringOperations.encodeRope(String.valueOf(value), UTF8Encoding.INSTANCE));
        }

    }

    @CoreMethod(names = "print_backtrace", onSingleton = true)
    public abstract static class PrintBacktraceNode extends CoreMethodNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject printBacktrace() {
            getContext().getCallStack().printBacktrace(this);
            return nil();
        }

    }

    @CoreMethod(names = "ast", onSingleton = true, required = 1)
    public abstract static class ASTNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "isRubyMethod(method)")
        public DynamicObject astMethod(DynamicObject method) {
            return ast(Layouts.METHOD.getMethod(method));
        }

        @Specialization(guards = "isRubyUnboundMethod(method)")
        public DynamicObject astUnboundMethod(DynamicObject method) {
            return ast(Layouts.UNBOUND_METHOD.getMethod(method));
        }

        @Specialization(guards = "isRubyProc(proc)")
        public DynamicObject astProc(DynamicObject proc) {
            return ast(Layouts.PROC.getMethod(proc));
        }

        @TruffleBoundary
        private DynamicObject ast(InternalMethod method) {
            if (method.getCallTarget() instanceof RootCallTarget) {
                return ast(((RootCallTarget) method.getCallTarget()).getRootNode());
            } else {
                return nil();
            }
        }

        private DynamicObject ast(Node node) {
            if (node == null) {
                return nil();
            }

            final List<Object> array = new ArrayList<>();

            array.add(getSymbol(node.getClass().getSimpleName()));

            for (Node child : node.getChildren()) {
                array.add(ast(child));
            }

            return createArray(array.toArray(), array.size());
        }

    }

    @CoreMethod(names = "ast_graph", onSingleton = true, required = 1)
    public abstract static class ASTGraphNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "isRubyMethod(method)")
        public DynamicObject astMethod(DynamicObject method) {
            return ast(Layouts.METHOD.getMethod(method));
        }

        @Specialization(guards = "isRubyUnboundMethod(method)")
        public DynamicObject astUnboundMethod(DynamicObject method) {
            return ast(Layouts.UNBOUND_METHOD.getMethod(method));
        }

        @Specialization(guards = "isRubyProc(proc)")
        public DynamicObject astProc(DynamicObject proc) {
            return ast(Layouts.PROC.getMethod(proc));
        }

        @TruffleBoundary
        private DynamicObject ast(InternalMethod method) {
            if (method.getCallTarget() instanceof RootCallTarget) {
                return ast(method.getName(), ((RootCallTarget) method.getCallTarget()).getRootNode());
            } else {
                return nil();
            }
        }

        private DynamicObject ast(String name, Node node) {
            if (node != null) {
                GraphPrintVisitor graphPrinter = new GraphPrintVisitor();
                graphPrinter.beginGraph(name).visit(node);

                graphPrinter.printToNetwork(true);
                graphPrinter.close();
            }
            return nil();
        }

    }

    @CoreMethod(names = "object_type_of", onSingleton = true, required = 1)
    public abstract static class ObjectTypeOfNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject objectTypeOf(DynamicObject value) {
            return getSymbol(value.getShape().getObjectType().getClass().getSimpleName());
        }
    }

    @CoreMethod(names = "shape", onSingleton = true, required = 1)
    public abstract static class ShapeNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject shape(DynamicObject object) {
            return createString(StringOperations.encodeRope(object.getShape().toString(), UTF8Encoding.INSTANCE));
        }

    }

    @CoreMethod(names = "array_storage", onSingleton = true, required = 1)
    public abstract static class ArrayStorageNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyArray(array)")
        public DynamicObject arrayStorage(DynamicObject array) {
            String storage = ArrayStrategy.of(array).toString();
            return StringOperations.createString(getContext(), StringOperations.encodeRope(storage, USASCIIEncoding.INSTANCE));
        }

    }

    @CoreMethod(names = "hash_storage", onSingleton = true, required = 1)
    public abstract static class HashStorageNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyHash(hash)")
        public DynamicObject hashStorage(DynamicObject hash) {
            Object store = Layouts.HASH.getStore(hash);
            String storage = store == null ? "null" : store.getClass().toString();
            return StringOperations.createString(getContext(), StringOperations.encodeRope(storage, USASCIIEncoding.INSTANCE));
        }

    }

    @CoreMethod(names = "shared?", onSingleton = true, required = 1)
    @ImportStatic(SharedObjects.class)
    public abstract static class IsSharedNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "object.getShape() == cachedShape",
                assumptions = "cachedShape.getValidAssumption()", limit = "getCacheLimit()")
        public boolean isSharedCached(DynamicObject object,
                @Cached("object.getShape()") Shape cachedShape,
                @Cached("isShared(getContext(), cachedShape)") boolean shared) {
            return shared;
        }

        @Specialization(replaces = "isSharedCached")
        public boolean isShared(DynamicObject object) {
            return SharedObjects.isShared(getContext(), object);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().INSTANCE_VARIABLE_CACHE;
        }

    }

    @CoreMethod(names = "resolve_lazy_nodes", onSingleton = true)
    public abstract static class ResolveLazyNodesNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject resolveLazyNodes() {
            LazyRubyNode.resolveAll(getContext());
            return nil();
        }

    }

    @CoreMethod(names = "log_warning", isModuleFunction = true, required = 1)
    public abstract static class LogWarningNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject logWarning(
                VirtualFrame frame, Object value,
                @Cached("create()") NameToJavaStringNode toJavaStringNode) {
            Log.warning(toJavaStringNode.executeToJavaString(frame, value));
            return nil();
        }

    }

    @CoreMethod(names = "throw_java_exception", onSingleton = true, required = 1)
    public abstract static class ThrowJavaExceptionNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject throwJavaException(Object message) {
            throw new RuntimeException(message.toString());
        }

    }

    @CoreMethod(names = "throw_java_exception_with_cause", onSingleton = true, required = 1)
    public abstract static class ThrowJavaExceptionWithCauseNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject throwJavaExceptionWithCause(Object message) {
            throw new RuntimeException(message.toString(), new RuntimeException("cause 1", new RuntimeException("cause 2")));
        }

    }

    @CoreMethod(names = "assert", onSingleton = true, required = 1)
    public abstract static class AssertNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject throwJavaException(boolean condition) {
            assert condition;
            return nil();
        }

    }

    @CoreMethod(names = "foreign_object", onSingleton = true)
    public abstract static class ForeignObjectNode extends CoreMethodArrayArgumentsNode {

        private static class ForeignObject implements TruffleObject {

            @Override
            public ForeignAccess getForeignAccess() {
                throw new UnsupportedOperationException();
            }
            
        }

        @TruffleBoundary
        @Specialization
        public Object resolveLazyNodes() {
            return new ForeignObject();
        }

    }

}
