/*
 * Copyright 2012 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.indexer.event;

import com.ngdata.sep.WALEditFilter;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IndexerEditFilterProviderTest {

    private IndexerEditFilterProvider filterProvider;

    @Before
    public void setUp() {
        filterProvider = new IndexerEditFilterProvider();
    }

    @Test
    public void testGetWALEditFilter_IndexUpdaterSubscription() {
        String subscriptionName = "IndexUpdater_IndexName";
        IndexerEditFilter editFilter = (IndexerEditFilter)filterProvider.getWALEditFilter(subscriptionName);
        assertEquals(subscriptionName, editFilter.getSubscriptionName());
    }

    @Test
    public void testGetWALEditFilter_LinkIndexUpdater() {
        String subscriptionName = "LinkIndexUpdater";
        WALEditFilter editFilter = filterProvider.getWALEditFilter(subscriptionName);
        assertTrue(editFilter instanceof LinkIndexUpdaterEditFilter);
    }

    @Test
    public void testGetWALEditFilter_NotIndexUpdaterSubscription() {
        assertNull(filterProvider.getWALEditFilter("NotAnIndexUpdaterSubscription"));
    }

}
