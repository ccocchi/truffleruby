/*
 * Copyright (c) 2013, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.symbol;

import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.RubyContext;
import org.truffleruby.collections.WeakValueCache;
import org.truffleruby.core.rope.NativeRope;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeCache;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.parser.Identifiers;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;

import java.util.Collection;

public class SymbolTable {

    private final RopeCache ropeCache;

    // A cache for j.l.String to Symbols. Entries are kept as long as the Symbol is alive.
    // However, this doesn't matter as the cache entries will be re-created when used.
    private final WeakValueCache<String, RubySymbol> stringToSymbolCache = new WeakValueCache<>();

    // Weak map of RopeKey to Symbol to keep Symbols unique.
    // As long as the Symbol is referenced, the entry will stay in the symbolMap.
    private final WeakValueCache<Rope, RubySymbol> symbolMap = new WeakValueCache<>();

    private final RubySymbol[] singleByteSymbols = new RubySymbol[256];

    public SymbolTable(RopeCache ropeCache) {
        this.ropeCache = ropeCache;
        addCoreSymbols();
    }

    private void addCoreSymbols() {
        for (RubySymbol symbol : CoreSymbols.CORE_SYMBOLS) {
            final Rope rope = symbol.getRope();
            assert rope == normalizeRopeForLookup(rope);
            assert rope == ropeCache.getRope(rope);

            final RubySymbol existing = symbolMap.put(rope, symbol);
            if (existing != null) {
                throw new AssertionError("Duplicate Symbol in SymbolTable: " + existing);
            }

            stringToSymbolCache.put(symbol.getString(), symbol);
        }
    }

    public RubySymbol getSingleByteSymbol(char index, BranchProfile nullProfile) {
        RubySymbol symbol = singleByteSymbols[index];
        if (symbol == null) {
            nullProfile.enter();
            symbol = getSymbol(String.valueOf(index));
            singleByteSymbols[index] = symbol;
        }
        return symbol;
    }

    @TruffleBoundary
    public RubySymbol getSymbol(String string) {
        RubySymbol symbol = stringToSymbolCache.get(string);
        if (symbol != null) {
            return symbol;
        }

        final Rope rope;
        if (StringOperations.isAsciiOnly(string)) {
            rope = RopeOperations.encodeAscii(string, USASCIIEncoding.INSTANCE);
        } else {
            rope = StringOperations.encodeRope(string, UTF8Encoding.INSTANCE);
        }
        symbol = getSymbol(rope);

        // Add it to the direct j.l.String to Symbol cache

        stringToSymbolCache.addInCacheIfAbsent(string, symbol);

        return symbol;
    }

    @TruffleBoundary
    public RubySymbol getSymbol(Rope rope) {
        final Rope normalizedRope = normalizeRopeForLookup(rope);
        final RubySymbol symbol = symbolMap.get(normalizedRope);
        if (symbol != null) {
            return symbol;
        }

        final Rope cachedRope = ropeCache.getRope(normalizedRope);
        final RubySymbol newSymbol = createSymbol(cachedRope);
        // Use a RopeKey with the cached Rope in symbolMap, since the Symbol refers to it and so we
        // do not keep rope alive unnecessarily.
        return symbolMap.addInCacheIfAbsent(cachedRope, newSymbol);
    }

    @TruffleBoundary
    public RubySymbol getSymbolIfExists(Rope rope) {
        final Rope ropeKey = normalizeRopeForLookup(rope);
        return symbolMap.get(ropeKey);
    }

    private Rope normalizeRopeForLookup(Rope rope) {
        if (rope instanceof NativeRope) {
            rope = ((NativeRope) rope).toLeafRope();
        }

        if (rope.isAsciiOnly() && rope.getEncoding() != USASCIIEncoding.INSTANCE) {
            rope = RopeOperations.withEncoding(rope, USASCIIEncoding.INSTANCE);
        }

        return rope;
    }

    private RubySymbol createSymbol(Rope cachedRope) {
        final String string = RopeOperations.decodeOrEscapeBinaryRope(cachedRope);
        return new RubySymbol(string, cachedRope);
    }

    // TODO (eregon, 10/10/2015): this check could be done when a Symbol is created to be much cheaper
    @TruffleBoundary(transferToInterpreterOnException = false)
    public static String checkInstanceVariableName(
            RubyContext context,
            String name,
            Object receiver,
            Node currentNode) {
        if (!Identifiers.isValidInstanceVariableName(name)) {
            throw new RaiseException(context, context.getCoreExceptions().nameErrorInstanceNameNotAllowable(
                    name,
                    receiver,
                    currentNode));
        }
        return name;
    }

    @TruffleBoundary(transferToInterpreterOnException = false)
    public static String checkClassVariableName(
            RubyContext context,
            String name,
            Object receiver,
            Node currentNode) {
        if (!Identifiers.isValidClassVariableName(name)) {
            throw new RaiseException(context, context.getCoreExceptions().nameErrorInstanceNameNotAllowable(
                    name,
                    receiver,
                    currentNode));
        }
        return name;
    }

    @TruffleBoundary
    public Object[] allSymbols() {
        final Collection<RubySymbol> allSymbols = symbolMap.values();
        // allSymbols is a private concrete collection not a view
        return allSymbols.toArray();
    }

}
