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
package org.lilyproject.indexer.worker;

public class IndexerWorkerSettings {
    private int listenersPerIndex = 10;
    private boolean enableLocking = false;
    private int solrMaxTotalConnections = 200;
    private int solrMaxConnectionsPerHost = 50;

    public int getListenersPerIndex() {
        return listenersPerIndex;
    }

    public void setListenersPerIndex(int listenersPerIndex) {
        this.listenersPerIndex = listenersPerIndex;
    }

    public boolean getEnableLocking() {
        return enableLocking;
    }

    public void setEnableLocking(boolean enableLocking) {
        this.enableLocking = enableLocking;
    }

    public int getSolrMaxTotalConnections() {
        return solrMaxTotalConnections;
    }

    public void setSolrMaxTotalConnections(int solrMaxTotalConnections) {
        this.solrMaxTotalConnections = solrMaxTotalConnections;
    }

    public int getSolrMaxConnectionsPerHost() {
        return solrMaxConnectionsPerHost;
    }

    public void setSolrMaxConnectionsPerHost(int solrMaxConnectionsPerHost) {
        this.solrMaxConnectionsPerHost = solrMaxConnectionsPerHost;
    }
}
