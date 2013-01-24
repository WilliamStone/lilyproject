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

import org.lilyproject.sep.WALEditFilter;

import org.lilyproject.sep.WALEditFilterProvider;

/**
 * Provides configured instances of {@link IndexerEditFilter} for SEP subscriptions that are related
 * to an {@code IndexUpdater}.
 */
public class IndexerEditFilterProvider implements WALEditFilterProvider {

    @Override
    public WALEditFilter getWALEditFilter(String subscriptionId) {
        if (subscriptionId.startsWith("IndexUpdater_")) {
            return new IndexerEditFilter(subscriptionId);
        } else {
            return null;
        }
    }

}