/*
 * Copyright (c) 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.language.control.DynamicReturnException;
import org.truffleruby.language.control.LocalReturnException;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.control.RetryException;
import org.truffleruby.language.control.ReturnID;
import org.truffleruby.language.methods.SharedMethodInfo;
import org.truffleruby.language.methods.Split;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;
import org.truffleruby.language.methods.TranslateExceptionNode;
import org.truffleruby.language.methods.UnsupportedOperationBehavior;

public class RubyMethodRootNode extends RubyRootNode {

    private final ReturnID returnID;
    @CompilationFinal private TruffleLanguage.ContextReference<RubyContext> contextReference;
    @Child private TranslateExceptionNode translateExceptionNode;

    private final BranchProfile localReturnProfile = BranchProfile.create();
    private final ConditionProfile matchingReturnProfile = ConditionProfile.create();
    private final BranchProfile retryProfile = BranchProfile.create();

    public RubyMethodRootNode(
            RubyLanguage language,
            SourceSection sourceSection,
            FrameDescriptor frameDescriptor,
            SharedMethodInfo sharedMethodInfo,
            RubyNode body,
            Split split,
            ReturnID returnID) {
        super(language, sourceSection, frameDescriptor, sharedMethodInfo, body, split);
        this.returnID = returnID;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        SafepointManager.poll(language, this);

        try {
            return body.execute(frame);
        } catch (LocalReturnException e) {
            localReturnProfile.enter();
            return e.getValue();
        } catch (DynamicReturnException e) {
            if (matchingReturnProfile.profile(e.getReturnID() == returnID)) {
                return e.getValue();
            } else {
                throw e;
            }
        } catch (RetryException e) {
            retryProfile.enter();
            throw new RaiseException(getContext(), getContext().getCoreExceptions().syntaxErrorInvalidRetry(this));
        } catch (Throwable t) {
            if (translateExceptionNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                translateExceptionNode = insert(TranslateExceptionNode.create());
            }
            throw translateExceptionNode.executeTranslation(t, UnsupportedOperationBehavior.TYPE_ERROR);
        }
    }

    public TruffleLanguage.ContextReference<RubyContext> getContextReference() {
        if (contextReference == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            contextReference = lookupContextReference(RubyLanguage.class);
        }

        return contextReference;
    }

    public RubyContext getContext() {
        return getContextReference().get();
    }

}
