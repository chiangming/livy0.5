/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Indicates an API that is considered private to Livy and should not be used by client
 * applications.
 */
/*
* https://www.cnblogs.com/liangweiping/p/3837332.html
* define a user annotation:Private
* @Documented:this annotation is contained by Documented.
* @Retention(RetentionPolicy.CLASS):this annotation is contained by class file, but not in runtime.
* */
@Documented
@Retention(RetentionPolicy.CLASS)
public @interface Private { }
