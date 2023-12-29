/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.sitenetsoft.sunseterp.framework.base.conversion;

import java.util.Locale;
import java.util.TimeZone;

/** Abstract LocalizedConverter class. This class handles converter registration
 * and it implements the <code>canConvert</code>, <code>getSourceClass</code>,
 * and <code>getTargetClass</code> methods.
 */
public abstract class AbstractLocalizedConverter<S, T> extends AbstractConverter<S, T> implements LocalizedConverter<S, T> {
    AbstractLocalizedConverter(Class<? super S> sourceClass, Class<? super T> targetClass) {
        super(sourceClass, targetClass);
    }

    @Override
    public T convert(Class<? extends T> targetClass, S obj, Locale locale, TimeZone timeZone) throws ConversionException {
        return convert(obj, locale, timeZone);
    }

    @Override
    public T convert(Class<? extends T> targetClass, S obj, Locale locale, TimeZone timeZone, String formatString) throws ConversionException {
        return convert(obj, locale, timeZone, formatString);
    }
}