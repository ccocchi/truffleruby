/*
 * Copyright (c) 2016, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 *
 *
 * Some of the code in this class is modified from org.jruby.util.StringSupport,
 * licensed under the same EPL1.0/GPL 2.0/LGPL 2.1 used throughout.
 */

package org.truffleruby.core.rope;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.SlowPathException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.core.rope.RopeNodesFactory.SetByteNodeGen;
import org.truffleruby.core.string.StringAttributes;
import org.truffleruby.core.string.StringSupport;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.RubyBaseNode;
import org.truffleruby.language.control.RaiseException;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static org.truffleruby.core.rope.CodeRange.CR_7BIT;
import static org.truffleruby.core.rope.CodeRange.CR_BROKEN;
import static org.truffleruby.core.rope.CodeRange.CR_UNKNOWN;
import static org.truffleruby.core.rope.CodeRange.CR_VALID;

public abstract class RopeNodes {

    // Preserves encoding of the top-level Rope
    public abstract static class SubstringNode extends RubyBaseNode {

        @Child private MakeSubstringRopeNode makeSubstringRopeNode = MakeSubstringRopeNode.create();
        @Child private WithEncodingNode withEncodingNode;

        public static SubstringNode create() {
            return RopeNodesFactory.SubstringNodeGen.create();
        }

        public abstract Rope executeSubstring(Rope base, int byteOffset, int byteLength);

        @Specialization(guards = "byteLength == 0")
        public Rope substringZeroBytes(Rope base, int byteOffset, int byteLength,
                                       @Cached("create()") MakeLeafRopeNode makeLeafRopeNode) {
            return makeLeafRopeNode.executeMake(RopeConstants.EMPTY_BYTES, base.getEncoding(), CR_UNKNOWN, 0);
        }

        @Specialization(guards = "byteLength == 1")
        public Rope substringOneByte(Rope base, int byteOffset, int byteLength,
                                     @Cached("createBinaryProfile()") ConditionProfile isUTF8,
                                     @Cached("createBinaryProfile()") ConditionProfile isUSAscii,
                                     @Cached("createBinaryProfile()") ConditionProfile isAscii8Bit,
                                     @Cached("create()") GetByteNode getByteNode) {
            final int index = getByteNode.executeGetByte(base, byteOffset);

            if (isUTF8.profile(base.getEncoding() == UTF8Encoding.INSTANCE)) {
                return RopeConstants.UTF8_SINGLE_BYTE_ROPES[index];
            }

            if (isUSAscii.profile(base.getEncoding() == USASCIIEncoding.INSTANCE)) {
                return RopeConstants.US_ASCII_SINGLE_BYTE_ROPES[index];
            }

            if (isAscii8Bit.profile(base.getEncoding() == ASCIIEncoding.INSTANCE)) {
                return RopeConstants.ASCII_8BIT_SINGLE_BYTE_ROPES[index];
            }

            return withEncoding(RopeConstants.ASCII_8BIT_SINGLE_BYTE_ROPES[index], base.getEncoding());
        }

        @Specialization(guards = { "byteLength > 1", "sameAsBase(base, byteLength)" })
        public Rope substringSameAsBase(Rope base, int byteOffset, int byteLength) {
            return base;
        }

        @Specialization(guards = { "byteLength > 1", "!sameAsBase(base, byteLength)" })
        public Rope substringLeafRope(LeafRope base, int byteOffset, int byteLength) {
            return makeSubstringRopeNode.executeMake(base.getEncoding(), base, byteOffset, byteLength);
        }

        @Specialization(guards = { "byteLength > 1", "!sameAsBase(base, byteLength)" })
        public Rope substringSubstringRope(SubstringRope base, int byteOffset, int byteLength) {
            return substringSubstringRopeWithEncoding(base.getEncoding(), base, byteOffset, byteLength);
        }

        private Rope substringSubstringRopeWithEncoding(Encoding encoding, SubstringRope rope, int byteOffset, int byteLength) {
            return makeSubstringRopeNode.executeMake(encoding, rope.getChild(), byteOffset + rope.getByteOffset(), byteLength);
        }

        @Specialization(guards = { "byteLength > 1", "!sameAsBase(base, byteLength)" })
        public Rope substringRepeatingRope(RepeatingRope base, int byteOffset, int byteLength,
                                          @Cached("createBinaryProfile()") ConditionProfile matchesChildProfile) {
            return substringRepeatingRopeWithEncoding(base.getEncoding(), base, byteOffset, byteLength, matchesChildProfile);
        }

        private Rope substringRepeatingRopeWithEncoding(Encoding encoding, RepeatingRope rope, int byteOffset, int byteLength, ConditionProfile matchesChildProfile) {
            final boolean offsetFitsChild = byteOffset % rope.getChild().byteLength() == 0;
            final boolean byteLengthFitsChild = byteLength == rope.getChild().byteLength();

            // TODO (nirvdrum 07-Apr-16) We can specialize any number of children that fit perfectly into the length, not just count == 1. But we may need to create a new RepeatingNode to handle count > 1.
            if (matchesChildProfile.profile(offsetFitsChild && byteLengthFitsChild)) {
                return withEncoding(rope.getChild(), encoding);
            }

            return makeSubstringRopeNode.executeMake(encoding, rope, byteOffset, byteLength);
        }

        @Specialization(guards = { "byteLength > 1", "!sameAsBase(base, byteLength)" })
        public Rope substringLazyRope(LazyRope base, int byteOffset, int byteLength) {
            return makeSubstringRopeNode.executeMake(base.getEncoding(), base, byteOffset, byteLength);
        }

        @Specialization(guards = { "byteLength > 1", "!sameAsBase(base, byteLength)" })
        public Rope substringNativeRope(NativeRope base, int byteOffset, int byteLength,
                @Cached("create()") MakeLeafRopeNode makeLeafRopeNode) {
            return makeLeafRopeNode.executeMake(base.getBytes(byteOffset, byteLength), base.getEncoding(), CR_UNKNOWN, NotProvided.INSTANCE);
        }

        @Specialization(guards = { "byteLength > 1", "!sameAsBase(base, byteLength)" })
        public Rope substringConcatRope(ConcatRope base, int byteOffset, int byteLength,
                                        @Cached("createBinaryProfile()") ConditionProfile matchesChildProfile) {
            Rope root = base;

            while (root instanceof ConcatRope) {
                ConcatRope concatRoot = (ConcatRope) root;
                Rope left = concatRoot.getLeft();
                Rope right = concatRoot.getRight();

                // CASE 1: Fits in left.
                if (byteOffset + byteLength <= left.byteLength()) {
                    root = left;
                    continue;
                }

                // CASE 2: Fits in right.
                if (byteOffset >= left.byteLength()) {
                    byteOffset -= left.byteLength();
                    root = right;
                    continue;
                }

                // CASE 3: Spans left and right.
                if (byteLength == root.byteLength()) {
                    return withEncoding(root, base.getEncoding());
                } else {
                    return makeSubstringRopeNode.executeMake(base.getEncoding(), root, byteOffset, byteLength);
                }
            }

            if (root instanceof SubstringRope) {
                return substringSubstringRopeWithEncoding(base.getEncoding(), (SubstringRope) root, byteOffset, byteLength);
            } else if (root instanceof RepeatingRope) {
                return substringRepeatingRopeWithEncoding(base.getEncoding(), (RepeatingRope) root, byteOffset, byteLength, matchesChildProfile);
            }

            return makeSubstringRopeNode.executeMake(base.getEncoding(), root, byteOffset, byteLength);
        }

        private Rope withEncoding(Rope rope, Encoding encoding) {
            if (withEncodingNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                withEncodingNode = insert(WithEncodingNode.create());
            }

            return withEncodingNode.executeWithEncoding(rope, encoding);
        }

        protected static boolean sameAsBase(Rope base, int byteLength) {
            // A SubstringRope's byte length is not allowed to be larger than its child. Thus, if it has the same
            // byte length as its child, it must be logically equivalent to the child.
            return byteLength == base.byteLength();
        }

    }

    public abstract static class MakeSubstringRopeNode extends RubyBaseNode {

        @Child private MakeLeafRopeNode makeLeafRopeNode;

        public static MakeSubstringRopeNode create() {
            return RopeNodesFactory.MakeSubstringRopeNodeGen.create();
        }

        public abstract Rope executeMake(Encoding encoding, Rope base, int byteOffset, int byteLength);

        @Specialization(guards = "base.isAsciiOnly()")
        public Rope makeSubstring7Bit(Encoding encoding, ManagedRope base, int byteOffset, int byteLength) {
            if (getContext().getOptions().ROPE_LAZY_SUBSTRINGS) {
                return new SubstringRope(encoding, base, byteOffset, byteLength, byteLength, CR_7BIT);
            } else {
                return new AsciiOnlyLeafRope(RopeOperations.extractRange(base, byteOffset, byteLength), encoding);
            }
        }

        @Specialization(guards = "!base.isAsciiOnly()")
        public Rope makeSubstringNon7Bit(Encoding encoding, ManagedRope base, int byteOffset, int byteLength,
                @Cached("create()") CalculateAttributesNode calculateAttributesNode) {

            final StringAttributes attributes = calculateAttributesNode.executeCalculateAttributes(encoding, RopeOperations.extractRange(base, byteOffset, byteLength));

            final CodeRange codeRange = attributes.getCodeRange();
            final int characterLength = attributes.getCharacterLength();

            if (getContext().getOptions().ROPE_LAZY_SUBSTRINGS) {
                return new SubstringRope(encoding, base, byteOffset, byteLength, characterLength, codeRange);
            } else {
                if (makeLeafRopeNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    makeLeafRopeNode = insert(RopeNodes.MakeLeafRopeNode.create());
                }

                final byte[] bytes = RopeOperations.extractRange(base, byteOffset, byteLength);

                return makeLeafRopeNode.executeMake(bytes, encoding, codeRange, characterLength);
            }
        }

        @Specialization
        public Rope makeSubstringNativeRope(Encoding encoding, NativeRope base, int byteOffset, int byteLength,
                @Cached("createBinaryProfile()") ConditionProfile asciiOnlyProfile,
                @Cached("create()") AsciiOnlyNode asciiOnlyNode,
                @Cached("create()") MakeLeafRopeNode makeLeafRopeNode) {
            final byte[] bytes = new byte[byteLength];
            base.copyTo(byteOffset, bytes, 0, byteLength);

            final CodeRange codeRange;
            final Object characterLength;

            if (asciiOnlyProfile.profile(asciiOnlyNode.execute(base))) {
                codeRange = CR_7BIT;
                characterLength = byteLength;
            } else {
                codeRange = CR_UNKNOWN;
                characterLength = NotProvided.INSTANCE;
            }

            return makeLeafRopeNode.executeMake(bytes, encoding, codeRange, characterLength);
        }

    }

    @ImportStatic(RopeGuards.class)
    public abstract static class CalculateAttributesNode extends RubyBaseNode {

        public static CalculateAttributesNode create() {
            return RopeNodesFactory.CalculateAttributesNodeGen.create();
        }

        abstract StringAttributes executeCalculateAttributes(Encoding encoding, byte[] bytes);

        @Specialization(guards = "isEmpty(bytes)")
        public StringAttributes calculateAttributesEmpty(Encoding encoding, byte[] bytes,
                                                  @Cached("createBinaryProfile()") ConditionProfile isAsciiCompatible) {
            return new StringAttributes(0,
                    isAsciiCompatible.profile(encoding.isAsciiCompatible()) ? CR_7BIT : CR_VALID);
        }

        @Specialization(guards = { "!isEmpty(bytes)", "isBinaryString(encoding)" })
        public StringAttributes calculateAttributesBinaryString(Encoding encoding, byte[] bytes,
                                                         @Cached("create()") BranchProfile nonAsciiStringProfile) {
            CodeRange codeRange = CR_7BIT;

            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] < 0) {
                    nonAsciiStringProfile.enter();
                    codeRange = CR_VALID;
                    break;
                }
            }

            return new StringAttributes(bytes.length, codeRange);
        }

        @Specialization(rewriteOn = NonAsciiCharException.class,
                guards = { "!isEmpty(bytes)", "!isBinaryString(encoding)", "isAsciiCompatible(encoding)" })
        public StringAttributes calculateAttributesAsciiCompatible(Encoding encoding, byte[] bytes) throws NonAsciiCharException {
            // Optimistically assume this string consists only of ASCII characters. If a non-ASCII character is found,
            // fail over to a more generalized search.
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] < 0) {
                    throw new NonAsciiCharException();
                }
            }

            return new StringAttributes(bytes.length, CR_7BIT);
        }

        @Specialization(replaces = "calculateAttributesAsciiCompatible",
                guards = { "!isEmpty(bytes)", "!isBinaryString(encoding)", "isAsciiCompatible(encoding)" })
        public StringAttributes calculateAttributesAsciiCompatibleGeneric(Encoding encoding, byte[] bytes,
                @Cached("create()") CalculateCharacterLengthNode calculateCharacterLengthNode,
                @Cached("createBinaryProfile()") ConditionProfile validCharacterProfile) {
            // Taken from StringSupport.strLengthWithCodeRangeAsciiCompatible.

            CodeRange codeRange = CR_7BIT;
            int characters = 0;
            int p = 0;
            final int end = bytes.length;

            while (p < end) {
                if (Encoding.isAscii(bytes[p])) {
                    final int multiByteCharacterPosition = StringSupport.searchNonAscii(bytes, p, end);
                    if (multiByteCharacterPosition == -1) {
                        return new StringAttributes(characters + (end - p), codeRange);
                    }

                    characters += multiByteCharacterPosition - p;
                    p = multiByteCharacterPosition;
                }

                final int lengthOfCurrentCharacter = calculateCharacterLengthNode.characterLength(encoding, CR_UNKNOWN, bytes, p, end);

                if (validCharacterProfile.profile(lengthOfCurrentCharacter > 0)) {
                    if (codeRange != CR_BROKEN) {
                        codeRange = CR_VALID;
                    }

                    p += lengthOfCurrentCharacter;
                } else {
                    codeRange = CR_BROKEN;
                    p++;
                }

                characters++;
            }

            return new StringAttributes(characters, codeRange);
        }

        @Specialization(guards = { "!isEmpty(bytes)", "!isBinaryString(encoding)", "!isAsciiCompatible(encoding)" })
        public StringAttributes calculateAttributesGeneric(Encoding encoding, byte[] bytes,
                @Cached("create()") CalculateCharacterLengthNode calculateCharacterLengthNode,
                @Cached("createBinaryProfile()") ConditionProfile validCharacterProfile) {
            // Taken from StringSupport.strLengthWithCodeRangeNonAsciiCompatible.

            CodeRange codeRange = CR_VALID;
            int characters;
            int p = 0;
            final int end = bytes.length;

            for (characters = 0; p < end; characters++) {
                final int lengthOfCurrentCharacter = calculateCharacterLengthNode.characterLength(encoding, CR_UNKNOWN, bytes, p, end);

                if (validCharacterProfile.profile(lengthOfCurrentCharacter > 0)) {
                    if (codeRange != CR_BROKEN) {
                        codeRange = CR_VALID;
                    }

                    p += lengthOfCurrentCharacter;
                } else {
                    codeRange = CR_BROKEN;
                    p++;
                }
            }

            return new StringAttributes(characters, codeRange);
        }

        protected static final class NonAsciiCharException extends SlowPathException {
            private static final long serialVersionUID = 5550642254188358382L;
        }

    }

    public abstract static class ConcatNode extends RubyBaseNode {

        public static ConcatNode create() {
            return RopeNodesFactory.ConcatNodeGen.create();
        }

        @Child private FlattenNode flattenNode;

        public abstract Rope executeConcat(Rope left, Rope right, Encoding encoding);

        @Specialization
        public Rope concatNativeRopeLeft(NativeRope left, Rope right, Encoding encoding,
                @Cached("create()") NativeToManagedNode nativeToManagedNode,
                @Cached("createBinaryProfile()") ConditionProfile emptyNativeRopeProfile) {
            if (emptyNativeRopeProfile.profile(left.isEmpty())) {
                return right;
            } else {
                return executeConcat(nativeToManagedNode.execute(left), right, encoding);
            }
        }

        @Specialization
        public Rope concatNativeRopeRight(Rope left, NativeRope right, Encoding encoding,
                @Cached("create()") NativeToManagedNode nativeToManagedNode,
                @Cached("createBinaryProfile()") ConditionProfile emptyNativeRopeProfile) {
            if (emptyNativeRopeProfile.profile(right.isEmpty())) {
                return left;
            } else {
                return executeConcat(left, nativeToManagedNode.execute(right), encoding);
            }
        }

        @Specialization(guards = "left.isEmpty()")
        public Rope concatLeftEmpty(Rope left, ManagedRope right, Encoding encoding,
                @Cached("create()") WithEncodingNode withEncodingNode) {
            return withEncodingNode.executeWithEncoding(right, encoding);
        }

        @Specialization(guards = "right.isEmpty()")
        public Rope concatRightEmpty(ManagedRope left, Rope right, Encoding encoding,
                @Cached("create()") WithEncodingNode withEncodingNode) {
            return withEncodingNode.executeWithEncoding(left, encoding);
        }

        @Specialization(guards = { "!left.isEmpty()", "!right.isEmpty()", "!isCodeRangeBroken(left, right)" })
        public Rope concat(ManagedRope left, ManagedRope right, Encoding encoding,
                           @Cached("createBinaryProfile()") ConditionProfile sameCodeRangeProfile,
                           @Cached("createBinaryProfile()") ConditionProfile brokenCodeRangeProfile,
                           @Cached("createBinaryProfile()") ConditionProfile isLeftSingleByteOptimizableProfile,
                           @Cached("createBinaryProfile()") ConditionProfile shouldRebalanceProfile) {
            try {
                Math.addExact(left.byteLength(), right.byteLength());
            } catch (ArithmeticException e) {
                throw new RaiseException(getContext(), getContext().getCoreExceptions().argumentError("Result of string concatenation exceeds the system maximum string length", this));
            }

            if (shouldRebalanceProfile.profile(left.depth() >= getContext().getOptions().ROPE_DEPTH_THRESHOLD && left instanceof ConcatRope)) {
                left = rebalance((ConcatRope) left, getContext().getOptions().ROPE_DEPTH_THRESHOLD, getFlattenNode());
            }

            if (shouldRebalanceProfile.profile(right.depth() >= getContext().getOptions().ROPE_DEPTH_THRESHOLD && right instanceof ConcatRope)) {
                right = rebalance((ConcatRope) right, getContext().getOptions().ROPE_DEPTH_THRESHOLD, getFlattenNode());
            }

            int depth = depth(left, right);
            /*if (depth >= 10) {
                System.out.println("ConcatRope depth: " + depth);
            }*/

            return new ConcatRope(left, right, encoding,
                    commonCodeRange(left.getCodeRange(), right.getCodeRange(), sameCodeRangeProfile, brokenCodeRangeProfile),
                    depth, isBalanced(left, right));
        }

        private FlattenNode getFlattenNode() {
            if (flattenNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                flattenNode = insert(FlattenNode.create());
            }
            return flattenNode;
        }

        private boolean isBalanced(Rope left, Rope right) {
            // Our definition of balanced is centered around the notion of rebalancing. We could have a simple structure
            // such as ConcatRope(ConcatRope(LeafRope, LeafRope), LeafRope) that is balanced on its own but may contribute
            // to an unbalanced rope when combined with another rope of similar structure. To keep things simple, we only
            // consider ConcatRopes that consist of two non-ConcatRopes balanced as the base case and ConcatRopes that
            // have balanced ConcatRopes for both children are balanced by induction.
            if (left instanceof ConcatRope) {
                if (right instanceof ConcatRope) {
                    return ((ConcatRope) left).isBalanced() && ((ConcatRope) right).isBalanced();
                }

                return false;
            } else {
                // We treat the concatenation of two non-ConcatRopes as balanced, even if their children not balanced.
                // E.g., a SubstringRope whose child is an unbalanced ConcatRope arguable isn't balanced. However,
                // the code is much simpler by handling it this way. Balanced ConcatRopes will never rebalance, but
                // if they become part of a larger subtree that exceeds the depth threshold, they may be flattened.
                return !(right instanceof ConcatRope);
            }
        }

        @TruffleBoundary
        private ManagedRope rebalance(ConcatRope rope, int depthThreshold, FlattenNode flattenNode) {
            Deque<ManagedRope> currentRopeQueue = new ArrayDeque<>();
            Deque<ManagedRope> nextLevelQueue = new ArrayDeque<>();

            linearizeTree(rope.getLeft(), currentRopeQueue);
            linearizeTree(rope.getRight(), currentRopeQueue);

            final int flattenThreshold = depthThreshold / 2;

            ManagedRope root = null;
            while (!currentRopeQueue.isEmpty()) {
                ManagedRope left = currentRopeQueue.pop();

                if (left.depth() >= flattenThreshold) {
                    left = flattenNode.executeFlatten(left);
                }

                if (currentRopeQueue.isEmpty()) {
                    if (nextLevelQueue.isEmpty()) {
                        root = left;
                    } else {
                        // If a rope can't be paired with another rope at the current level (i.e., odd numbers of ropes),
                        // it needs to be promoted to the next level where it will be tried again. Since by definition
                        // every rope already present in the next level must have occurred before this rope in the current
                        // level, this rope must be added to the end of the list in the next level to maintain proper
                        // position.
                        nextLevelQueue.add(left);
                    }
                } else {
                    ManagedRope right = currentRopeQueue.pop();

                    if (right.depth() >= flattenThreshold) {
                        right = flattenNode.executeFlatten(right);
                    }

                    final ManagedRope child = new ConcatRope(left, right, rope.getEncoding(),
                                                      commonCodeRange(left.getCodeRange(), right.getCodeRange()),
                            depth(left, right), isBalanced(left, right));

                    nextLevelQueue.add(child);
                }

                if (currentRopeQueue.isEmpty() && !nextLevelQueue.isEmpty()) {
                    currentRopeQueue = nextLevelQueue;
                    nextLevelQueue = new ArrayDeque<>();
                }
            }

            return root;
        }

        @TruffleBoundary
        private void linearizeTree(ManagedRope rope, Deque<ManagedRope> ropeQueue) {
            if (rope instanceof ConcatRope) {
                final ConcatRope concatRope = (ConcatRope) rope;

                // If a rope is known to be balanced, there's no need to rebalance it.
                if (concatRope.isBalanced()) {
                    ropeQueue.add(concatRope);
                } else {
                    linearizeTree(concatRope.getLeft(), ropeQueue);
                    linearizeTree(concatRope.getRight(), ropeQueue);
                }
            } else {
                // We never rebalance non-ConcatRopes since that requires per-rope type logic with likely minimal benefit.
                ropeQueue.add(rope);
            }
        }

        @Specialization(guards = { "!left.isEmpty()", "!right.isEmpty()", "isCodeRangeBroken(left, right)" })
        public Rope concatCrBroken(ManagedRope left, ManagedRope right, Encoding encoding,
                                   @Cached("create()") MakeLeafRopeNode makeLeafRopeNode) {
            // This specialization was added to a special case where broken code range(s),
            // may concat to form a valid code range.
            try {
                Math.addExact(left.byteLength(), right.byteLength());
            } catch (ArithmeticException e) {
                throw new RaiseException(getContext(), getContext().getCoreExceptions().argumentError("Result of string concatenation exceeds the system maximum string length", this));
            }

            final byte[] leftBytes = left.getBytes();
            final byte[] rightBytes = right.getBytes();
            final byte[] bytes = new byte[leftBytes.length + rightBytes.length];
            System.arraycopy(leftBytes, 0, bytes, 0, leftBytes.length);
            System.arraycopy(rightBytes, 0, bytes, leftBytes.length, rightBytes.length);
            return makeLeafRopeNode.executeMake(bytes, encoding, CR_UNKNOWN, NotProvided.INSTANCE);
        }

        public static CodeRange commonCodeRange(CodeRange first, CodeRange second,
                                                ConditionProfile sameCodeRangeProfile,
                                                ConditionProfile brokenCodeRangeProfile) {
            if (sameCodeRangeProfile.profile(first == second)) {
                return first;
            }

            if (brokenCodeRangeProfile.profile((first == CR_BROKEN) || (second == CR_BROKEN))) {
                return CR_BROKEN;
            }

            // If we get this far, one must be CR_7BIT and the other must be CR_VALID, so promote to the more general code range.
            return CR_VALID;
        }

        public static CodeRange commonCodeRange(CodeRange first, CodeRange second) {
            if (first == second) {
                return first;
            }

            if ((first == CR_BROKEN) || (second == CR_BROKEN)) {
                return CR_BROKEN;
            }

            // If we get this far, one must be CR_7BIT and the other must be CR_VALID, so promote to the more general code range.
            return CR_VALID;
        }

        private int depth(Rope left, Rope right) {
            return Math.max(left.depth(), right.depth()) + 1;
        }

        protected static boolean isCodeRangeBroken(ManagedRope first, ManagedRope second) {
            return first.getCodeRange() == CR_BROKEN || second.getCodeRange() == CR_BROKEN;
        }
    }

    @ImportStatic(RopeGuards.class)
    public abstract static class MakeLeafRopeNode extends RubyBaseNode {

        public static MakeLeafRopeNode create() {
            return RopeNodesFactory.MakeLeafRopeNodeGen.create();
        }

        public abstract LeafRope executeMake(byte[] bytes, Encoding encoding, CodeRange codeRange, Object characterLength);

        @Specialization(guards = "is7Bit(codeRange)")
        public LeafRope makeAsciiOnlyLeafRope(byte[] bytes, Encoding encoding, CodeRange codeRange, Object characterLength) {
            return new AsciiOnlyLeafRope(bytes, encoding);
        }

        @Specialization(guards = "isValid(codeRange)")
        public LeafRope makeValidLeafRopeWithCharacterLength(byte[] bytes, Encoding encoding, CodeRange codeRange, int characterLength) {
            return new ValidLeafRope(bytes, encoding, characterLength);
        }

        @Specialization(guards = { "isValid(codeRange)", "isFixedWidth(encoding)" })
        public LeafRope makeValidLeafRopeFixedWidthEncoding(byte[] bytes, Encoding encoding, CodeRange codeRange, NotProvided characterLength) {
            final int calculatedCharacterLength = bytes.length / encoding.minLength();

            return new ValidLeafRope(bytes, encoding, calculatedCharacterLength);
        }

        @Specialization(guards = { "isValid(codeRange)", "!isFixedWidth(encoding)", "isAsciiCompatible(encoding)" })
        public LeafRope makeValidLeafRopeAsciiCompat(byte[] bytes, Encoding encoding, CodeRange codeRange, NotProvided characterLength,
                @Cached("create()") BranchProfile errorProfile,
                @Cached("create()") CalculateCharacterLengthNode calculateCharacterLengthNode) {
            // Extracted from StringSupport.strLength.

            int calculatedCharacterLength = 0;
            int p = 0;
            int e = bytes.length;

            while (p < e) {
                if (Encoding.isAscii(bytes[p])) {
                    int q = StringSupport.searchNonAscii(bytes, p, e);
                    if (q == -1) {
                        calculatedCharacterLength += (e - p);
                        break;
                    }
                    calculatedCharacterLength += q - p;
                    p = q;
                }

                final int delta = calculateCharacterLengthNode.characterLengthWithRecovery(encoding, CR_VALID, bytes, p, e);
                if (delta < 0) {
                    errorProfile.enter();
                    throw new UnsupportedOperationException("Code range is reported as valid, but is invalid for the given encoding: " + encodingName(encoding));
                }

                p += delta;
                calculatedCharacterLength++;
            }

            return new ValidLeafRope(bytes, encoding, calculatedCharacterLength);
        }

        @Specialization(guards = { "isValid(codeRange)", "!isFixedWidth(encoding)", "!isAsciiCompatible(encoding)" })
        public LeafRope makeValidLeafRope(byte[] bytes, Encoding encoding, CodeRange codeRange, NotProvided characterLength) {
            // Extracted from StringSupport.strLength.

            int calculatedCharacterLength;
            int p = 0;
            int e = bytes.length;

            for (calculatedCharacterLength = 0; p < e; calculatedCharacterLength++) {
                p += StringSupport.characterLength(encoding, codeRange, bytes, p, e);
            }

            return new ValidLeafRope(bytes, encoding, calculatedCharacterLength);
        }

        @Specialization(guards = "isBroken(codeRange)")
        public LeafRope makeInvalidLeafRope(byte[] bytes, Encoding encoding, CodeRange codeRange, Object characterLength) {
            return new InvalidLeafRope(bytes, encoding, RopeOperations.strLength(encoding, bytes, 0, bytes.length));
        }

        @Specialization(guards = { "isUnknown(codeRange)", "isEmpty(bytes)" })
        public LeafRope makeUnknownLeafRopeEmpty(byte[] bytes, Encoding encoding, CodeRange codeRange, Object characterLength,
                                                 @Cached("createBinaryProfile()") ConditionProfile isUTF8,
                                                 @Cached("createBinaryProfile()") ConditionProfile isUSAscii,
                                                 @Cached("createBinaryProfile()") ConditionProfile isAscii8Bit,
                                                 @Cached("createBinaryProfile()") ConditionProfile isAsciiCompatible) {
            if (isUTF8.profile(encoding == UTF8Encoding.INSTANCE)) {
                return RopeConstants.EMPTY_UTF8_ROPE;
            }

            if (isUSAscii.profile(encoding == USASCIIEncoding.INSTANCE)) {
                return RopeConstants.EMPTY_US_ASCII_ROPE;
            }

            if (isAscii8Bit.profile(encoding == ASCIIEncoding.INSTANCE)) {
                return RopeConstants.EMPTY_ASCII_8BIT_ROPE;
            }

            if (isAsciiCompatible.profile(encoding.isAsciiCompatible())) {
                return new AsciiOnlyLeafRope(RopeConstants.EMPTY_BYTES, encoding);
            }

            return new ValidLeafRope(RopeConstants.EMPTY_BYTES, encoding, 0);
        }

        @Specialization(guards = { "isUnknown(codeRange)", "!isEmpty(bytes)" })
        public LeafRope makeUnknownLeafRopeGeneric(byte[] bytes, Encoding encoding, CodeRange codeRange, Object characterLength,
                                                   @Cached("create()") CalculateAttributesNode calculateAttributesNode,
                                                   @Cached("create()") BranchProfile asciiOnlyProfile,
                                                   @Cached("create()") BranchProfile validProfile,
                                                   @Cached("create()") BranchProfile brokenProfile,
                                                   @Cached("create()") BranchProfile errorProfile) {
            final StringAttributes attributes = calculateAttributesNode.executeCalculateAttributes(encoding, bytes);

            switch(attributes.getCodeRange()) {
                case CR_7BIT: {
                    asciiOnlyProfile.enter();
                    return new AsciiOnlyLeafRope(bytes, encoding);
                }

                case CR_VALID: {
                    validProfile.enter();
                    return new ValidLeafRope(bytes, encoding, attributes.getCharacterLength());
                }

                case CR_BROKEN: {
                    brokenProfile.enter();
                    return new InvalidLeafRope(bytes, encoding, attributes.getCharacterLength());
                }

                default: {
                    errorProfile.enter();
                    throw new UnsupportedOperationException("CR_UNKNOWN encountered, but code range should have been calculated");
                }
            }
        }

        protected static boolean is7Bit(CodeRange codeRange) {
            return codeRange == CR_7BIT;
        }

        protected static boolean isValid(CodeRange codeRange) {
            return codeRange == CR_VALID;
        }

        protected static boolean isBroken(CodeRange codeRange) {
            return codeRange == CR_BROKEN;
        }

        protected static boolean isUnknown(CodeRange codeRange) {
            return codeRange == CodeRange.CR_UNKNOWN;
        }

        protected static boolean isFixedWidth(Encoding encoding) {
            return encoding.isFixedWidth();
        }

        @TruffleBoundary
        private String encodingName(Encoding encoding) {
            return encoding.toString();
        }

    }

    @ImportStatic(RopeGuards.class)
    public abstract static class RepeatNode extends RubyBaseNode {

        public static RepeatNode create() {
            return RopeNodesFactory.RepeatNodeGen.create();
        }

        public abstract Rope executeRepeat(Rope base, int times);

        @Specialization(guards = "times == 0")
        public Rope repeatZero(Rope base, int times,
                               @Cached("create()") WithEncodingNode withEncodingNode) {
            return withEncodingNode.executeWithEncoding(RopeConstants.EMPTY_UTF8_ROPE, base.getEncoding());
        }

        @Specialization(guards = "times == 1")
        public Rope repeatOne(Rope base, int times) {
            return base;
        }

        @TruffleBoundary
        @Specialization(guards = { "isSingleByteString(base)", "times > 1" })
        public Rope multiplySingleByteString(Rope base, int times,
                                             @Cached("create()") MakeLeafRopeNode makeLeafRopeNode) {
            final byte filler = base.getBytes()[0];

            byte[] buffer = new byte[times];
            Arrays.fill(buffer, filler);

            return makeLeafRopeNode.executeMake(buffer, base.getEncoding(), base.getCodeRange(), times);
        }

        @Specialization(guards = { "!isSingleByteString(base)", "times > 1" })
        public Rope repeatManaged(ManagedRope base, int times) {
            try {
                Math.multiplyExact(base.byteLength(), times);
            } catch (ArithmeticException e) {
                throw new RaiseException(getContext(), getContext().getCoreExceptions().argumentError("Result of repeating string exceeds the system maximum string length", this));
            }

            return new RepeatingRope(base, times);
        }

        @Specialization(guards = { "!isSingleByteString(base)", "times > 1" })
        public Rope repeatNative(NativeRope base, int times,
                @Cached("create()") NativeToManagedNode nativeToManagedNode) {
            return executeRepeat(nativeToManagedNode.execute(base), times);
        }

    }

    public abstract static class DebugPrintRopeNode extends RubyBaseNode {

        public abstract DynamicObject executeDebugPrint(Rope rope, int currentLevel, boolean printString);

        @TruffleBoundary
        @Specialization
        public DynamicObject debugPrintLeafRope(LeafRope rope, int currentLevel, boolean printString) {
            printPreamble(currentLevel);

            // Converting a rope to a java.lang.String may populate the byte[], so we need to query for the array status beforehand.
            final boolean bytesAreNull = rope.getRawBytes() == null;

            System.err.println(StringUtils.format("%s (%s; BN: %b; BL: %d; CL: %d; CR: %s; D: %d; E: %s)",
                    printString ? rope.toString() : "<skipped>",
                    rope.getClass().getSimpleName(),
                    bytesAreNull,
                    rope.byteLength(),
                    rope.characterLength(),
                    rope.getCodeRange(),
                    rope.depth(),
                    rope.getEncoding()));

            return nil();
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject debugPrintSubstringRope(SubstringRope rope, int currentLevel, boolean printString) {
            printPreamble(currentLevel);

            // Converting a rope to a java.lang.String may populate the byte[], so we need to query for the array status beforehand.
            final boolean bytesAreNull = rope.getRawBytes() == null;

            System.err.println(StringUtils.format("%s (%s; BN: %b; BL: %d; CL: %d; CR: %s; O: %d; D: %d; E: %s)",
                    printString ? rope.toString() : "<skipped>",
                    rope.getClass().getSimpleName(),
                    bytesAreNull,
                    rope.byteLength(),
                    rope.characterLength(),
                    rope.getCodeRange(),
                    rope.getByteOffset(),
                    rope.depth(),
                    rope.getEncoding()));

            executeDebugPrint(rope.getChild(), currentLevel + 1, printString);

            return nil();
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject debugPrintConcatRope(ConcatRope rope, int currentLevel, boolean printString) {
            printPreamble(currentLevel);

            // Converting a rope to a java.lang.String may populate the byte[], so we need to query for the array status beforehand.
            final boolean bytesAreNull = rope.getRawBytes() == null;

            System.err.println(StringUtils.format("%s (%s; BN: %b; BL: %d; CL: %d; CR: %s; D: %d; LD: %d; RD: %d; E: %s)",
                    printString ? rope.toString() : "<skipped>",
                    rope.getClass().getSimpleName(),
                    bytesAreNull,
                    rope.byteLength(),
                    rope.characterLength(),
                    rope.getCodeRange(),
                    rope.depth(),
                    rope.getLeft().depth(),
                    rope.getRight().depth(),
                    rope.getEncoding()));

            executeDebugPrint(rope.getLeft(), currentLevel + 1, printString);
            executeDebugPrint(rope.getRight(), currentLevel + 1, printString);

            return nil();
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject debugPrintRepeatingRope(RepeatingRope rope, int currentLevel, boolean printString) {
            printPreamble(currentLevel);

            // Converting a rope to a java.lang.String may populate the byte[], so we need to query for the array status beforehand.
            final boolean bytesAreNull = rope.getRawBytes() == null;

            System.err.println(StringUtils.format("%s (%s; BN: %b; BL: %d; CL: %d; CR: %s; T: %d; D: %d; E: %s)",
                    printString ? rope.toString() : "<skipped>",
                    rope.getClass().getSimpleName(),
                    bytesAreNull,
                    rope.byteLength(),
                    rope.characterLength(),
                    rope.getCodeRange(),
                    rope.getTimes(),
                    rope.depth(),
                    rope.getEncoding()));

            executeDebugPrint(rope.getChild(), currentLevel + 1, printString);

            return nil();
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject debugPrintLazyInt(LazyIntRope rope, int currentLevel, boolean printString) {
            printPreamble(currentLevel);

            // Converting a rope to a java.lang.String may populate the byte[], so we need to query for the array status beforehand.
            final boolean bytesAreNull = rope.getRawBytes() == null;

            System.err.println(StringUtils.format("%s (%s; BN: %b; BL: %d; CL: %d; CR: %s; V: %d, D: %d; E: %s)",
                    printString ? rope.toString() : "<skipped>",
                    rope.getClass().getSimpleName(),
                    bytesAreNull,
                    rope.byteLength(),
                    rope.characterLength(),
                    rope.getCodeRange(),
                    rope.getValue(),
                    rope.depth(),
                    rope.getEncoding()));

            return nil();
        }

        private void printPreamble(int level) {
            if (level > 0) {
                for (int i = 0; i < level; i++) {
                    System.err.print("|  ");
                }
            }
        }

    }

    public abstract static class WithEncodingNode extends RubyBaseNode {

        @Child private BytesNode bytesNode;
        @Child private MakeLeafRopeNode makeLeafRopeNode;

        public static WithEncodingNode create() {
            return RopeNodesFactory.WithEncodingNodeGen.create();
        }

        public abstract Rope executeWithEncoding(Rope rope, Encoding encoding);

        @Specialization(guards = "rope.getEncoding() == encoding")
        protected Rope withEncodingSameEncoding(Rope rope, Encoding encoding) {
            return rope;
        }

        @Specialization(guards = "rope.getEncoding() != encoding")
        protected Rope nativeRopeWithEncoding(NativeRope rope, Encoding encoding) {
            return rope.withEncoding(encoding, rope.getCodeRange());
        }

        @Specialization(guards = {
                "rope.getEncoding() != encoding",
                "rope.getClass() == cachedRopeClass",
        }, limit = "getCacheLimit()")
        protected Rope withEncodingAsciiCompatible(ManagedRope rope, Encoding encoding,
                @Cached("rope.getClass()") Class<? extends Rope> cachedRopeClass,
                @Cached("createBinaryProfile()") ConditionProfile asciiCompatibleProfile,
                @Cached("createBinaryProfile()") ConditionProfile asciiOnlyProfile,
                @Cached("createBinaryProfile()") ConditionProfile binaryEncodingProfile) {

            if (asciiCompatibleProfile.profile(encoding.isAsciiCompatible())) {
                if (asciiOnlyProfile.profile(rope.isAsciiOnly())) {
                    // ASCII-only strings can trivially convert to other ASCII-compatible encodings.
                    return cachedRopeClass.cast(rope).withEncoding(encoding, CR_7BIT);
                } else if (binaryEncodingProfile.profile(encoding == ASCIIEncoding.INSTANCE &&
                        rope.getCodeRange() == CR_VALID &&
                        rope.getEncoding().isAsciiCompatible())) {
                    // ASCII-compatible CR_VALID strings are also CR_VALID in binary.
                    return cachedRopeClass.cast(rope).withEncoding(ASCIIEncoding.INSTANCE, CR_VALID);
                } else {
                    // The rope either has a broken code range or isn't ASCII-compatible. In the case of a broken
                    // code range, we must perform a new code range scan with the target encoding to see if it's still
                    // broken. In the case of a non-ASCII-compatible encoding we don't have a quick way to reinterpret
                    // the byte sequence.
                    return rescanBytesForEncoding(rope, encoding);
                }
            } else {
                // We don't know of any good way to quickly reinterpret bytes from two different encodings, so we
                // must perform a full code range scan and character length calculation.
                return rescanBytesForEncoding(rope, encoding);
            }
        }

        // Version without a node
        @TruffleBoundary
        public static Rope withEncodingSlow(Rope originalRope, Encoding newEncoding) {
            if (originalRope.getEncoding() == newEncoding) {
                return originalRope;
            }

            if (originalRope.getCodeRange() == CR_7BIT && newEncoding.isAsciiCompatible()) {
                return originalRope.withEncoding(newEncoding, CR_7BIT);
            }

            if (newEncoding == ASCIIEncoding.INSTANCE && originalRope.getCodeRange() == CR_VALID && originalRope.getEncoding().isAsciiCompatible()) {
                // ASCII-compatible CR_VALID strings are also CR_VALID in binary.
                return originalRope.withEncoding(newEncoding, CR_VALID);
            }

            return RopeOperations.create(originalRope.getBytes(), newEncoding, CR_UNKNOWN);
        }

        private Rope rescanBytesForEncoding(Rope rope, Encoding encoding) {
            if (bytesNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                bytesNode = insert(BytesNode.create());
            }

            if (makeLeafRopeNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                makeLeafRopeNode = insert(MakeLeafRopeNode.create());
            }

            return makeLeafRopeNode.executeMake(bytesNode.execute(rope), encoding, CR_UNKNOWN, NotProvided.INSTANCE);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ROPE_CLASS_CACHE;
        }

    }

    public abstract static class GetByteNode extends RubyBaseNode {

        public static GetByteNode create() {
            return RopeNodesFactory.GetByteNodeGen.create();
        }

        public abstract int executeGetByte(Rope rope, int index);

        @Specialization(guards = "rope.getRawBytes() != null")
        public int getByte(Rope rope, int index) {
            return rope.getRawBytes()[index] & 0xff;
        }

        @Specialization(guards = "rope.getRawBytes() == null")
        public int getByte(NativeRope rope, int index) {
            return rope.getByteSlow(index) & 0xff;
        }

        @Specialization(guards = "rope.getRawBytes() == null")
        public int getByte(LazyRope rope, int index) {
            return rope.getBytes()[index] & 0xff;
        }

        @Specialization(guards = "rope.getRawBytes() == null")
        public int getByteSubstringRope(SubstringRope rope, int index,
                @Cached("createBinaryProfile()") ConditionProfile childRawBytesNullProfile,
                @Cached("create()") ByteSlowNode slowByte) {
            if (childRawBytesNullProfile.profile(rope.getChild().getRawBytes() == null)) {
                return slowByte.execute(rope, index) & 0xff;
            }

            return rope.getChild().getRawBytes()[index + rope.getByteOffset()] & 0xff;
        }

        @Specialization(guards = "rope.getRawBytes() == null")
        public int getByteRepeatingRope(RepeatingRope rope, int index,
                                        @Cached("createBinaryProfile()") ConditionProfile childRawBytesNullProfile) {
            if (childRawBytesNullProfile.profile(rope.getChild().getRawBytes() == null)) {
                return rope.getByteSlow(index) & 0xff;
            }

            return rope.getChild().getRawBytes()[index % rope.getChild().byteLength()] & 0xff;
        }

        @Specialization(guards = "rope.getRawBytes() == null")
        public int getByteConcatRope(ConcatRope rope, int index,
                @Cached("createBinaryProfile()") ConditionProfile chooseLeftChildProfile,
                @Cached("createBinaryProfile()") ConditionProfile leftChildRawBytesNullProfile,
                @Cached("createBinaryProfile()") ConditionProfile rightChildRawBytesNullProfile,
                @Cached("create()") ByteSlowNode byteSlowLeft,
                @Cached("create()") ByteSlowNode byteSlowRight) {
            if (chooseLeftChildProfile.profile(index < rope.getLeft().byteLength())) {
                if (leftChildRawBytesNullProfile.profile(rope.getLeft().getRawBytes() == null)) {
                    return byteSlowLeft.execute(rope.getLeft(), index) & 0xff;
                }

                return rope.getLeft().getRawBytes()[index] & 0xff;
            }

            if (rightChildRawBytesNullProfile.profile(rope.getRight().getRawBytes() == null)) {
                return byteSlowRight.execute(rope.getRight(), index - rope.getLeft().byteLength()) & 0xff;
            }

            return rope.getRight().getRawBytes()[index - rope.getLeft().byteLength()] & 0xff;
        }

    }

    public abstract static class SetByteNode extends RubyBaseNode {

        @Child private ConcatNode composedConcatNode = ConcatNode.create();
        @Child private ConcatNode middleConcatNode = ConcatNode.create();
        @Child private MakeLeafRopeNode makeLeafRopeNode = MakeLeafRopeNode.create();
        @Child private SubstringNode leftSubstringNode = SubstringNode.create();
        @Child private SubstringNode rightSubstringNode = SubstringNode.create();

        public static SetByteNode create() {
            return SetByteNodeGen.create();
        }

        public abstract Rope executeSetByte(Rope string, int index, int value);

        @Specialization
        public Rope setByte(ManagedRope rope, int index, int value) {
            assert 0 <= index && index < rope.byteLength();

            final Rope left = leftSubstringNode.executeSubstring(rope, 0, index);
            final Rope right = rightSubstringNode.executeSubstring(rope, index + 1, rope.byteLength() - index - 1);
            final Rope middle = makeLeafRopeNode.executeMake(new byte[]{ (byte) value }, rope.getEncoding(), CodeRange.CR_UNKNOWN, NotProvided.INSTANCE);
            final Rope composed = composedConcatNode.executeConcat(middleConcatNode.executeConcat(left, middle, rope.getEncoding()), right, rope.getEncoding());

            return composed;
        }

        @Specialization
        public Rope setByte(NativeRope rope, int index, int value) {
            rope.set(index, value);
            return rope;
        }

    }

    public abstract static class GetCodePointNode extends RubyBaseNode {

        @Child private CalculateCharacterLengthNode calculateCharacterLengthNode;
        @Child SingleByteOptimizableNode singleByteOptimizableNode = SingleByteOptimizableNode.create();

        public static GetCodePointNode create() {
            return RopeNodesFactory.GetCodePointNodeGen.create();
        }

        public abstract int executeGetCodePoint(Rope rope, int index);

        @Specialization(guards = "singleByteOptimizableNode.execute(rope)")
        public int getCodePointSingleByte(Rope rope, int index,
                                          @Cached("create()") GetByteNode getByteNode) {
            return getByteNode.executeGetByte(rope, index);
        }

        @Specialization(guards = { "!singleByteOptimizableNode.execute(rope)", "rope.getEncoding().isUTF8()" })
        public int getCodePointUTF8(Rope rope, int index,
                @Cached("create()") GetByteNode getByteNode,
                @Cached("create()") BytesNode bytesNode,
                @Cached("create()") CodeRangeNode codeRangeNode,
                @Cached("createBinaryProfile()") ConditionProfile singleByteCharProfile,
                @Cached("create()") BranchProfile errorProfile) {
            final int firstByte = getByteNode.executeGetByte(rope, index);
            if (singleByteCharProfile.profile(firstByte < 128)) {
                return firstByte;
            }

            return getCodePointMultiByte(rope, index, errorProfile, bytesNode, codeRangeNode);
        }

        @Specialization(guards = { "!singleByteOptimizableNode.execute(rope)", "!rope.getEncoding().isUTF8()" })
        public int getCodePointMultiByte(Rope rope, int index,
                @Cached("create()") BranchProfile errorProfile,
                @Cached("create()") BytesNode bytesNode,
                @Cached("create()") CodeRangeNode codeRangeNode) {
            final byte[] bytes = bytesNode.execute(rope);
            final Encoding encoding = rope.getEncoding();
            final CodeRange codeRange = codeRangeNode.execute(rope);

            final int characterLength = characterLength(encoding, codeRange, bytes, index, rope.byteLength());
            if (characterLength <= 0) {
                errorProfile.enter();
                throw new RaiseException(getContext(), getContext().getCoreExceptions().argumentError("invalid byte sequence in " + encoding, null));
            }

            return mbcToCode(encoding, bytes, index, rope.byteLength());
        }

        @TruffleBoundary
        private int mbcToCode(Encoding encoding, byte[] bytes, int start, int end) {
            return encoding.mbcToCode(bytes, start, end);
        }

        private int characterLength(Encoding encoding, CodeRange codeRange, byte[] bytes, int start, int end) {
            if (calculateCharacterLengthNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                calculateCharacterLengthNode = insert(CalculateCharacterLengthNode.create());
            }

            return calculateCharacterLengthNode.characterLength(encoding, codeRange, bytes, start, end);
        }

    }

    @ImportStatic(RopeGuards.class)
    public abstract static class FlattenNode extends RubyBaseNode {

        @Child private MakeLeafRopeNode makeLeafRopeNode = MakeLeafRopeNode.create();

        public static FlattenNode create() {
            return RopeNodesFactory.FlattenNodeGen.create();
        }

        public abstract LeafRope executeFlatten(Rope rope);

        @Specialization
        public LeafRope flattenLeafRope(LeafRope rope) {
            return rope;
        }

        @Specialization
        public LeafRope flattenNativeRope(NativeRope rope,
                @Cached("create()") NativeToManagedNode nativeToManagedNode) {
            return nativeToManagedNode.execute(rope);
        }

        @Specialization(guards = { "!isLeafRope(rope)", "rope.getRawBytes() != null" })
        public LeafRope flattenNonLeafWithBytes(ManagedRope rope) {
            return makeLeafRopeNode.executeMake(rope.getRawBytes(), rope.getEncoding(), rope.getCodeRange(), rope.characterLength());
        }

        @Specialization(guards = { "!isLeafRope(rope)", "rope.getRawBytes() == null" })
        public LeafRope flatten(ManagedRope rope) {
            // NB: We call RopeOperations.flatten here rather than Rope#getBytes so we don't populate the byte[] in
            // the source `rope`. Otherwise, we'll end up a fully populated reference in both the source `rope` and the
            // flattened one, which could adversely affect GC.
            final byte[] bytes = RopeOperations.flattenBytes(rope);

            return makeLeafRopeNode.executeMake(bytes, rope.getEncoding(), rope.getCodeRange(), rope.characterLength());
        }

    }

    public abstract static class EqualNode extends RubyBaseNode {

        public static EqualNode create() {
            return RopeNodesFactory.EqualNodeGen.create();
        }

        public abstract boolean execute(Rope a, Rope b);

        @Specialization(guards = "a == b")
        public boolean sameRopeEqual(Rope a, Rope b) {
            return true;
        }

        @Specialization
        public boolean ropesEqual(Rope a, Rope b,
                @Cached("create()") BranchProfile differentEncodingProfile,
                @Cached("create()") BytesEqualNode bytesEqualNode) {
            if (a.getEncoding() != b.getEncoding()) {
                differentEncodingProfile.enter();
                return false;
            }

            return bytesEqualNode.execute(a, b);
        }

    }

    // This node type checks for the equality of the bytes owned by a rope but does not pay
    // attention to the encoding.
    public abstract static class BytesEqualNode extends RubyBaseNode {

        public static BytesEqualNode create() {
            return RopeNodesFactory.BytesEqualNodeGen.create();
        }

        public abstract boolean execute(Rope a, Rope b);

        @Specialization(guards = "a == b")
        public boolean sameRopes(Rope a, Rope b) {
            return true;
        }

        @Specialization(guards = {
                "a == cachedA",
                "b == cachedB",
        }, limit = "getDefaultCacheLimit()")
        public boolean cachedRopes(Rope a, Rope b,
                                   @Cached("a") Rope cachedA,
                                   @Cached("b") Rope cachedB,
                                   @Cached("a.bytesEqual(b)") boolean equal) {
            return equal;
        }

        @Specialization(guards = {
                "a != b",
                "a.getRawBytes() != null",
                "a.getRawBytes() == b.getRawBytes()" })
        public boolean sameByteArrays(Rope a, Rope b) {
            return true;
        }

        @Specialization(guards = {
                "a != b",
                "a.getRawBytes() != null",
                "b.getRawBytes() != null",
                "a.byteLength() == 1",
                "b.byteLength() == 1" })
        public boolean characterEqual(Rope a, Rope b) {
            return a.getRawBytes()[0] == b.getRawBytes()[0];
        }

        @Specialization(guards = "a != b", replaces = { "sameByteArrays", "characterEqual" })
        public boolean fullRopeEqual(Rope a, Rope b,
                @Cached("createBinaryProfile()") ConditionProfile aRawBytesProfile,
                @Cached("create()") BranchProfile sameByteArraysProfile,
                @Cached("create()") BranchProfile differentLengthProfile,
                @Cached("createBinaryProfile()") ConditionProfile aCalculatedHashProfile,
                @Cached("createBinaryProfile()") ConditionProfile bCalculatedHashProfile,
                @Cached("create()") BranchProfile differentHashCodeProfile,
                @Cached("create()") BranchProfile compareBytesProfile,
                @Cached("create()") BytesNode aBytesNode,
                @Cached("create()") BytesNode bBytesNode) {
            if (aRawBytesProfile.profile(a.getRawBytes() != null) && a.getRawBytes() == b.getRawBytes()) {
                sameByteArraysProfile.enter();
                return true;
            }

            if (a.byteLength() != b.byteLength()) {
                differentLengthProfile.enter();
                return false;
            }

            if (aCalculatedHashProfile.profile(a.isHashCodeCalculated()) &&
                    bCalculatedHashProfile.profile(b.isHashCodeCalculated())) {
                if (a.calculatedHashCode() != b.calculatedHashCode()) {
                    differentHashCodeProfile.enter();
                    return false;
                }
            }

            compareBytesProfile.enter();

            final byte[] aBytes = aBytesNode.execute(a);
            final byte[] bBytes = bBytesNode.execute(b);
            assert aBytes.length == bBytes.length;

            return Arrays.equals(aBytes, bBytes);
        }

    }

    public abstract static class BytesNode extends RubyBaseNode {

        public static BytesNode create() {
            return RopeNodesFactory.BytesNodeGen.create();
        }

        public abstract byte[] execute(Rope rope);

        @Specialization(guards = "rope.getRawBytes() != null")
        protected byte[] getBytesManaged(ManagedRope rope) {
            return rope.getRawBytes();
        }

        @Specialization
        protected byte[] getBytesNative(NativeRope rope) {
            return rope.getBytes();
        }

        @TruffleBoundary
        @Specialization(guards = "rope.getRawBytes() == null")
        protected byte[] getBytesFromRope(ManagedRope rope) {
            return rope.getBytes();
        }
    }

    public abstract static class ByteSlowNode extends RubyBaseNode {

        public static ByteSlowNode create() {
            return RopeNodesFactory.ByteSlowNodeGen.create();
        }

        public abstract byte execute(Rope rope, int index);

        @Specialization
        public byte getByteFromSubString(SubstringRope rope, int index,
                @Cached("create()") ByteSlowNode childNode) {
            return childNode.execute(rope.getChild(), rope.getByteOffset() + index);
        }

        @Specialization(guards = "rope.getRawBytes() != null")
        public byte fastByte(ManagedRope rope, int index) {
            return rope.getRawBytes()[index];
        }

        @Specialization
        public byte getByteFromNativeRope(NativeRope rope, int index) {
            return rope.getByteSlow(index);
        }

        @TruffleBoundary
        @Specialization(guards = "rope.getRawBytes() == null")
        public byte getByteFromRope(ManagedRope rope, int index) {
            return rope.getByteSlow(index);
        }
    }

    public abstract static class AsciiOnlyNode extends RubyBaseNode {

        public static AsciiOnlyNode create() {
            return RopeNodesFactory.AsciiOnlyNodeGen.create();
        }

        public abstract boolean execute(Rope rope);

        @Specialization
        public boolean asciiOnly(Rope rope,
                @Cached("create()") CodeRangeNode codeRangeNode) {
            return codeRangeNode.execute(rope) == CR_7BIT;
        }

    }

    public abstract static class CodeRangeNode extends RubyBaseNode {

        public static CodeRangeNode create() {
            return RopeNodesFactory.CodeRangeNodeGen.create();
        }

        public abstract CodeRange execute(Rope rope);

        @Specialization
        public CodeRange getCodeRangeManaged(ManagedRope rope) {
            return rope.getCodeRange();
        }

        @Specialization
        public CodeRange getCodeRangeNative(NativeRope rope,
                @Cached("create()") CalculateAttributesNode calculateAttributesNode,
                @Cached("createBinaryProfile()") ConditionProfile unknownCodeRangeProfile) {
            if (unknownCodeRangeProfile.profile(rope.getRawCodeRange() == CR_UNKNOWN)) {
                final StringAttributes attributes = calculateAttributesNode.executeCalculateAttributes(rope.getEncoding(), rope.getBytes());
                rope.updateAttributes(attributes);
                return attributes.getCodeRange();
            } else {
                return rope.getRawCodeRange();
            }
        }

    }

    public abstract static class HashNode extends RubyBaseNode {

        public static HashNode create() {
            return RopeNodesFactory.HashNodeGen.create();
        }

        public abstract int execute(Rope rope);

        @Specialization(guards = "rope.isHashCodeCalculated()")
        public int executeHashCalculated(Rope rope) {
            return rope.calculatedHashCode();
        }

        @Specialization(guards = "!rope.isHashCodeCalculated()")
        public int executeHashNotCalculated(Rope rope) {
            return rope.hashCode();
        }

    }

    public abstract static class CharacterLengthNode extends RubyBaseNode {

        public static CharacterLengthNode create() {
            return RopeNodesFactory.CharacterLengthNodeGen.create();
        }

        public abstract int execute(Rope rope);

        @Specialization
        public int getCharacterLengthManaged(ManagedRope rope) {
            return rope.characterLength();
        }

        @Specialization
        public int getCharacterLengthNative(NativeRope rope,
                @Cached("create()") CalculateAttributesNode calculateAttributesNode,
                @Cached("createBinaryProfile()") ConditionProfile unknownCharacterLengthProfile) {
            if (unknownCharacterLengthProfile.profile(rope.rawCharacterLength() == NativeRope.UNKNOWN_CHARACTER_LENGTH)) {
                final StringAttributes attributes = calculateAttributesNode.executeCalculateAttributes(rope.getEncoding(), rope.getBytes());
                rope.updateAttributes(attributes);
                return attributes.getCharacterLength();
            } else {
                return rope.rawCharacterLength();
            }
        }

    }

    public abstract static class SingleByteOptimizableNode extends RubyBaseNode {

        public static SingleByteOptimizableNode create() {
            return RopeNodesFactory.SingleByteOptimizableNodeGen.create();
        }

        public abstract boolean execute(Rope rope);

        @Specialization
        public boolean isSingleByteOptimizable(Rope rope,
                @Cached("create()") AsciiOnlyNode asciiOnlyNode,
                @Cached("createBinaryProfile()") ConditionProfile asciiOnlyProfile) {
            final boolean asciiOnly = asciiOnlyNode.execute(rope);

            if (asciiOnlyProfile.profile(asciiOnly)) {
                return true;
            } else {
                return rope.getEncoding().isSingleByte();
            }
        }

    }

    @ImportStatic(CodeRange.class)
    public abstract static class CalculateCharacterLengthNode extends RubyBaseNode {

        public static CalculateCharacterLengthNode create() {
            return RopeNodesFactory.CalculateCharacterLengthNodeGen.create();
        }

        protected abstract int executeLength(Encoding encoding, CodeRange codeRange, byte[] bytes,
                int byteOffset, int byteEnd, boolean recoverIfBroken);

        /**
         * This method returns the byte length for the first character encountered in `bytes`, starting at
         * `byteOffset` and ending at `byteEnd`. The validity of a character is defined by the `encoding`. If
         * the `codeRange` for the byte sequence is known for the supplied `encoding`, it should be passed to
         * help short-circuit some validation checks. If the `codeRange` is not known for the supplied `encoding`,
         * then `CodeRange.CR_UNKNOWN` should be passed. If the byte sequence is invalid, a negative value will
         * be returned. See `Encoding#length` for details on how to interpret the return value.
         */
        public int characterLength(Encoding encoding, CodeRange codeRange, byte[] bytes, int byteOffset, int byteEnd) {
            return executeLength(encoding, codeRange, bytes, byteOffset, byteEnd, false);
        }

        /**
         * This method works very similarly to `characterLength` and maintains the same invariants on inputs.
         * Where it differs is in the treatment of invalid byte sequences. Whereas `characterLength` will
         * return a negative value, this method will always return a positive value. MRI provides an arbitrary,
         * but deterministic, algorithm for returning a byte length for invalid byte sequences. This method is
         * to be used when the `codeRange` might be `CodeRange.CR_BROKEN` and the caller must handle the case
         * without raising an error. E.g., if `String#each_char` is called on a String that is `CR_BROKEN`, you
         * wouldn't want negative byte lengths to be returned because it would break iterating through the bytes.
         */
        public int characterLengthWithRecovery(Encoding encoding, CodeRange codeRange, byte[] bytes, int byteOffset, int byteEnd) {
            return executeLength(encoding, codeRange, bytes, byteOffset, byteEnd, true);
        }

        @Specialization(guards = "codeRange == CR_7BIT")
        protected int cr7Bit(Encoding encoding, CodeRange codeRange, byte[] bytes, int byteOffset, int byteEnd, boolean recoverIfBroken) {
            assert byteOffset < byteEnd;
            return 1;
        }

        @Specialization(guards = { "codeRange == CR_VALID", "encoding.isUTF8()" })
        protected int validUtf8(Encoding encoding, CodeRange codeRange, byte[] bytes, int byteOffset, int byteEnd, boolean recoverIfBroken,
                @Cached("create()") BranchProfile oneByteProfile,
                @Cached("create()") BranchProfile twoBytesProfile,
                @Cached("create()") BranchProfile threeBytesProfile,
                @Cached("create()") BranchProfile fourBytesProfile) {
            final byte b = bytes[byteOffset];
            final int ret;

            if (b >= 0) {
                oneByteProfile.enter();
                ret = 1;
            } else {
                switch(b & 0xf0) {
                    case 0xe0:
                        threeBytesProfile.enter();
                        ret = 3;
                        break;
                    case 0xf0:
                        fourBytesProfile.enter();
                        ret = 4;
                        break;
                    default:
                        twoBytesProfile.enter();
                        ret = 2;
                        break;
                }
            }

            return ret;
        }

        @Specialization(guards = { "codeRange == CR_VALID", "encoding.isAsciiCompatible()"})
        protected int validAsciiCompatible(Encoding encoding, CodeRange codeRange, byte[] bytes, int byteOffset, int byteEnd, boolean recoverIfBroken,
                @Cached("createBinaryProfile()") ConditionProfile asciiCharProfile) {
            if (asciiCharProfile.profile(bytes[byteOffset] >= 0)) {
                return 1;
            } else {
                return encodingLength(encoding, bytes, byteOffset, byteEnd);
            }
        }

        @Specialization(guards = { "codeRange == CR_VALID", "encoding.isFixedWidth()"})
        protected int validFixedWidth(Encoding encoding, CodeRange codeRange, byte[] bytes, int byteOffset, int byteEnd, boolean recoverIfBroken) {
            final int width = encoding.minLength();
            assert (byteEnd - byteOffset) >= width;
            return width;
        }

        @Specialization(guards = {
                "codeRange == CR_VALID",
                "!encoding.isAsciiCompatible()", // UTF-8 is ASCII-compatible, so we don't need to check the encoding is not UTF-8 here.
                "!encoding.isFixedWidth()"
        })
        protected int validGeneral(Encoding encoding, CodeRange codeRange, byte[] bytes, int byteOffset, int byteEnd, boolean recoverIfBroken) {
            return encodingLength(encoding, bytes, byteOffset, byteEnd);
        }

        @Specialization(guards = { "codeRange == CR_BROKEN || codeRange == CR_UNKNOWN", "recoverIfBroken" })
        protected int brokenOrUnknownWithRecovery(Encoding encoding, CodeRange codeRange, byte[] bytes,
                int byteOffset, int byteEnd, boolean recoverIfBroken,
                @Cached("createBinaryProfile()") ConditionProfile validCharWidthProfile,
                @Cached("createBinaryProfile()") ConditionProfile minEncodingWidthUsedProfile) {
            final int bytesRemaining = byteEnd - byteOffset;
            final int width = encodingLength(encoding, bytes, byteOffset, byteEnd);

            if (validCharWidthProfile.profile(width > 0 && width <= bytesRemaining)) {
                return width;
            } else {
                final int minEncodingWidth = encoding.minLength();

                if (minEncodingWidthUsedProfile.profile(minEncodingWidth <= bytesRemaining)) {
                    return minEncodingWidth;
                } else {
                    return bytesRemaining;
                }
            }
        }

        @Specialization(guards = { "codeRange == CR_BROKEN || codeRange == CR_UNKNOWN", "!recoverIfBroken" })
        protected int brokenOrUnknownWithoutRecovery(Encoding encoding, CodeRange codeRange, byte[] bytes,
                int byteOffset, int byteEnd, boolean recoverIfBroken,
                @Cached("createBinaryProfile()") ConditionProfile byteOffsetOutOfBoundsProfile,
                @Cached("createBinaryProfile()") ConditionProfile validCharWidthProfile) {
            final int bytesRemaining = byteEnd - byteOffset;

            if (byteOffsetOutOfBoundsProfile.profile(byteOffset >= byteEnd)) {
                return StringSupport.MBCLEN_NEEDMORE(1);
            } else {
                final int width = encodingLength(encoding, bytes, byteOffset, byteEnd);

                if (validCharWidthProfile.profile(width <= bytesRemaining)) {
                    return width;
                } else {
                    return StringSupport.MBCLEN_NEEDMORE(width - bytesRemaining);
                }
            }
        }

        @TruffleBoundary
        private int encodingLength(Encoding encoding, byte[] bytes, int byteOffset, int byteEnd) {
            return encoding.length(bytes, byteOffset, byteEnd);
        }

    }

    public abstract static class NativeToManagedNode extends RubyBaseNode {

        public static NativeToManagedNode create() {
            return RopeNodesFactory.NativeToManagedNodeGen.create();
        }

        public abstract LeafRope execute(NativeRope rope);

        @Specialization
        protected LeafRope nativeToManaged(NativeRope rope,
                @Cached("create()") BytesNode bytesNode,
                @Cached("create()") MakeLeafRopeNode makeLeafRopeNode) {
            // Ideally, a NativeRope would always have an accurate code range and character length. However, in practice,
            // it's possible for a bad code range to be associated with the rope due to native memory being updated by
            // 3rd party libraries. So, we must re-calculate the code range and character length values upon conversion
            // to a ManagedRope.
            return makeLeafRopeNode.executeMake(bytesNode.execute(rope), rope.getEncoding(), CR_UNKNOWN, NotProvided.INSTANCE);
        }

    }

}
