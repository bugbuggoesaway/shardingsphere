/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.encrypt.rule;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public final class EncryptColumnTest {
    
    @Test
    public void assertGetAssistedQueryColumn() {
        assertTrue(new EncryptColumn(null, "cipherColumn", null, "assistedQueryColumn", null, "plainColumn", null, "encryptorName").getAssistedQueryColumn().isPresent());
    }
    
    @Test
    public void assertGetPlainColumn() {
        assertTrue(new EncryptColumn(null, "cipherColumn", null, "assistedQueryColumn", null, "plainColumn", null, "encryptorName").getPlainColumn().isPresent());
    }
    
    @Test
    public void assertGetDataTypeName() {
        assertThat(new EncryptColumn("BIT(5)", null, null, null, null, null, null, null).getDataTypeName(), is("bit"));
        assertThat(new EncryptColumn("TINYINT(5) UNSIGNED ZEROFILL", null, null, null, null, null, null, null).getDataTypeName(), is("tinyint"));
        assertThat(new EncryptColumn("DATE", null, null, null, null, null, null, null).getDataTypeName(), is("date"));
    }
}
