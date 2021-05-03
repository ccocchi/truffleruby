/*
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.interop;

import java.io.IOException;

import com.oracle.truffle.api.library.CachedLibrary;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.StringCachingGuards;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.control.RaiseException;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import org.truffleruby.language.library.RubyStringLibrary;

@CoreModule("Polyglot")
public abstract class PolyglotNodes {

    @CoreMethod(names = "eval", onSingleton = true, required = 2)
    @ImportStatic({ StringCachingGuards.class, StringOperations.class })
    @ReportPolymorphism
    public abstract static class EvalNode extends CoreMethodArrayArgumentsNode {

        @Specialization(
                guards = {
                        "stringsId.isRubyString(id)",
                        "stringsSource.isRubyString(source)",
                        "idEqualNode.execute(stringsId.getRope(id), cachedMimeType)",
                        "sourceEqualNode.execute(stringsSource.getRope(source), cachedSource)" },
                limit = "getCacheLimit()")
        protected Object evalCached(Object id, Object source,
                @CachedLibrary(limit = "2") RubyStringLibrary stringsId,
                @CachedLibrary(limit = "2") RubyStringLibrary stringsSource,
                @Cached("stringsId.getRope(id)") Rope cachedMimeType,
                @Cached("stringsSource.getRope(source)") Rope cachedSource,
                @Cached("create(parse(stringsId.getRope(id), stringsSource.getRope(source)))") DirectCallNode callNode,
                @Cached RopeNodes.EqualNode idEqualNode,
                @Cached RopeNodes.EqualNode sourceEqualNode) {
            return callNode.call(EMPTY_ARGUMENTS);
        }

        @Specialization(
                guards = { "stringsId.isRubyString(id)", "stringsSource.isRubyString(source)" },
                replaces = "evalCached")
        protected Object evalUncached(Object id, Object source,
                @CachedLibrary(limit = "2") RubyStringLibrary stringsId,
                @CachedLibrary(limit = "2") RubyStringLibrary stringsSource,
                @Cached IndirectCallNode callNode) {
            return callNode.call(parse(stringsId.getRope(id), stringsSource.getRope(source)), EMPTY_ARGUMENTS);
        }

        @TruffleBoundary
        protected CallTarget parse(Rope id, Rope code) {
            final String idString = RopeOperations.decodeRope(id);
            final String codeString = RopeOperations.decodeRope(code);
            final Source source = Source.newBuilder(idString, codeString, "(eval)").build();
            try {
                return getContext().getEnv().parsePublic(source);
            } catch (IllegalStateException e) {
                throw new RaiseException(getContext(), coreExceptions().argumentError(e.getMessage(), this));
            }
        }

        protected int getCacheLimit() {
            return getLanguage().options.EVAL_CACHE;
        }

    }

    @CoreMethod(
            names = "eval_file",
            onSingleton = true,
            required = 1,
            optional = 1,
            argumentNames = { "file_name_or_id", "file_name" })
    public abstract static class EvalFileNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "strings.isRubyString(fileName)")
        protected Object evalFile(Object fileName, NotProvided id,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            final Source source;
            //intern() to improve footprint
            final String path = strings.getJavaString(fileName).intern();
            try {
                final TruffleFile file = getContext().getEnv().getPublicTruffleFile(path);
                String language = Source.findLanguage(file);
                if (language == null) {
                    throw new RaiseException(
                            getContext(),
                            coreExceptions().argumentError(
                                    "Could not find language of file " + strings.getJavaString(fileName),
                                    this));
                }
                source = Source.newBuilder(language, file).build();
            } catch (IOException e) {
                throw new RaiseException(getContext(), coreExceptions().ioError(e, this));
            }

            return eval(source);
        }

        @TruffleBoundary
        @Specialization(
                guards = {
                        "stringsId.isRubyString(id)",
                        "stringsFileName.isRubyString(fileName)" })
        protected Object evalFile(Object id, Object fileName,
                @CachedLibrary(limit = "2") RubyStringLibrary stringsId,
                @CachedLibrary(limit = "2") RubyStringLibrary stringsFileName) {
            final String idString = stringsId.getJavaString(id);
            final Source source = getSource(idString, stringsFileName.getJavaString(fileName));
            return eval(source);
        }

        private Object eval(Source source) {
            final CallTarget callTarget;
            try {
                callTarget = getContext().getEnv().parsePublic(source);
            } catch (IllegalStateException e) {
                throw new RaiseException(getContext(), coreExceptions().argumentError(e.getMessage(), this));
            }
            return callTarget.call();
        }

        private Source getSource(String language, String fileName) {
            //intern() to improve footprint
            final String path = fileName.intern();
            try {
                final TruffleFile file = getContext().getEnv().getPublicTruffleFile(path);
                return Source.newBuilder(language, file).build();
            } catch (IOException e) {
                throw new RaiseException(getContext(), coreExceptions().ioError(e, this));
            }
        }

    }

}
