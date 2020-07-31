/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.support;

import org.truffleruby.interop.messages.IOMessages;
import org.truffleruby.language.RubyDynamicObject;

import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.object.Shape;

public final class RubyIO extends RubyDynamicObject {

    public static final int CLOSED_FD = -1;

    private int descriptor;

    public RubyIO(Shape shape, int descriptor) {
        super(shape);
        this.descriptor = descriptor;
    }

    public int getDescriptor() {
        return descriptor;
    }

    public void setDescriptor(int descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    @ExportMessage
    public Class<?> dispatch() {
        return IOMessages.class;
    }

}