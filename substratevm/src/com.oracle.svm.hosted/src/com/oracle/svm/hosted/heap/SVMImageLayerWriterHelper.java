/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.heap;

import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.CAPTURING_CLASS_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.CONSTRUCTOR_NAME;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.C_ENTRY_POINT_CALL_STUB_METHOD_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.FACTORY_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.GENERATED_SERIALIZATION_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.INSTANTIATED_TYPE_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.JNI_JAVA_CALL_VARIANT_WRAPPER_METHOD_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.LAMBDA_TYPE_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.NOT_AS_PUBLISHED_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.ORIGINAL_METHOD_ID_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.PROXY_TYPE_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.RAW_DECLARING_CLASS_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.RAW_TARGET_CONSTRUCTOR_CLASS_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.REFLECTION_EXPAND_SIGNATURE_METHOD_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.TARGET_CONSTRUCTOR_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.THROW_ALLOCATED_OBJECT_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.WRAPPED_MEMBER_ARGUMENTS_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.WRAPPED_MEMBER_CLASS_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.WRAPPED_MEMBER_NAME_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.WRAPPED_METHOD_TAG;
import static com.oracle.graal.pointsto.heap.ImageLayerSnapshotUtil.WRAPPED_TYPE_TAG;
import static com.oracle.svm.hosted.heap.SVMImageLayerSnapshotUtil.GENERATED_SERIALIZATION;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.util.Arrays;

import org.graalvm.collections.EconomicMap;

import com.oracle.graal.pointsto.heap.ImageLayerWriter;
import com.oracle.graal.pointsto.heap.ImageLayerWriterHelper;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.core.reflect.serialize.SerializationSupport;
import com.oracle.svm.hosted.code.CEntryPointCallStubMethod;
import com.oracle.svm.hosted.code.CEntryPointCallStubSupport;
import com.oracle.svm.hosted.code.FactoryMethod;
import com.oracle.svm.hosted.jni.JNIJavaCallVariantWrapperMethod;
import com.oracle.svm.hosted.reflect.ReflectionExpandSignatureMethod;
import com.oracle.svm.hosted.reflect.proxy.ProxyRenamingSubstitutionProcessor;

import jdk.graal.compiler.java.LambdaUtils;

public class SVMImageLayerWriterHelper extends ImageLayerWriterHelper {
    public SVMImageLayerWriterHelper(ImageLayerWriter imageLayerWriter) {
        super(imageLayerWriter);
    }

    @Override
    protected void persistType(AnalysisType type, EconomicMap<String, Object> typeMap) {
        if (type.toJavaName(true).contains(GENERATED_SERIALIZATION)) {
            typeMap.put(WRAPPED_TYPE_TAG, GENERATED_SERIALIZATION_TAG);
            var key = SerializationSupport.singleton().getKeyFromConstructorAccessorClass(type.getJavaClass());
            typeMap.put(RAW_DECLARING_CLASS_TAG, key.getDeclaringClass().getName());
            typeMap.put(RAW_TARGET_CONSTRUCTOR_CLASS_TAG, key.getTargetConstructorClass().getName());
        } else if (LambdaUtils.isLambdaType(type)) {
            typeMap.put(WRAPPED_TYPE_TAG, LAMBDA_TYPE_TAG);
            typeMap.put(CAPTURING_CLASS_TAG, LambdaUtils.capturingClass(type.toJavaName()));
        } else if (ProxyRenamingSubstitutionProcessor.isProxyType(type)) {
            typeMap.put(WRAPPED_TYPE_TAG, PROXY_TYPE_TAG);
        }
        super.persistType(type, typeMap);
    }

    @Override
    protected void persistMethod(AnalysisMethod method, EconomicMap<String, Object> methodMap) {
        if (method.wrapped instanceof FactoryMethod factoryMethod) {
            methodMap.put(WRAPPED_METHOD_TAG, FACTORY_TAG);
            AnalysisMethod targetConstructor = method.getUniverse().lookup(factoryMethod.getTargetConstructor());
            imageLayerWriter.persistAnalysisParsedGraph(targetConstructor);
            imageLayerWriter.persistMethod(targetConstructor);
            methodMap.put(TARGET_CONSTRUCTOR_TAG, targetConstructor.getId());
            methodMap.put(THROW_ALLOCATED_OBJECT_TAG, factoryMethod.throwAllocatedObject());
            AnalysisType instantiatedType = method.getUniverse().lookup(factoryMethod.getInstantiatedType());
            methodMap.put(INSTANTIATED_TYPE_TAG, instantiatedType.getId());
        } else if (method.wrapped instanceof CEntryPointCallStubMethod cEntryPointCallStubMethod) {
            methodMap.put(WRAPPED_METHOD_TAG, C_ENTRY_POINT_CALL_STUB_METHOD_TAG);
            AnalysisMethod originalMethod = CEntryPointCallStubSupport.singleton().getMethodForStub(cEntryPointCallStubMethod);
            methodMap.put(ORIGINAL_METHOD_ID_TAG, originalMethod.getId());
            methodMap.put(NOT_AS_PUBLISHED_TAG, cEntryPointCallStubMethod.isNotPublished());
        } else if (method.wrapped instanceof ReflectionExpandSignatureMethod reflectionExpandSignatureMethod) {
            methodMap.put(WRAPPED_METHOD_TAG, REFLECTION_EXPAND_SIGNATURE_METHOD_TAG);
            Executable member = reflectionExpandSignatureMethod.getMember();
            persistWrappedMember(methodMap, member);
        } else if (method.wrapped instanceof JNIJavaCallVariantWrapperMethod jniJavaCallVariantWrapperMethod) {
            Executable executable = jniJavaCallVariantWrapperMethod.getMember();
            methodMap.put(WRAPPED_METHOD_TAG, JNI_JAVA_CALL_VARIANT_WRAPPER_METHOD_TAG);
            persistWrappedMember(methodMap, executable);
        }
        super.persistMethod(method, methodMap);
    }

    private static void persistWrappedMember(EconomicMap<String, Object> methodMap, Executable member) {
        methodMap.put(WRAPPED_MEMBER_CLASS_TAG, member.getDeclaringClass().getName());
        methodMap.put(WRAPPED_MEMBER_NAME_TAG, member instanceof Constructor<?> ? CONSTRUCTOR_NAME : member.getName());
        methodMap.put(WRAPPED_MEMBER_ARGUMENTS_TAG, Arrays.stream(member.getParameters()).map(p -> p.getType().getName()).toList());
    }
}
