/*
   Copyright 2011-2014 Lukas Vlcek

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

var bigdesk_charts = {
    default: {
        width: 270,
        height: 160
    }
};

bigdesk_charts.not_available = {

    chart: function(element) {
        return chartNotAvailable()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .svg(element).show();
    }
};

bigdesk_charts.channels = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Channels",
                series1: "HTTP",
                series2: "Transport",
                margin_left: 5,
                margin_bottom: 6,
                width: 80})
            .svg(element);
    },

    series1: function(stats){
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.http.current_open
            }
        })
    },

    series2: function(stats){
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.transport.server_open
            }
        })
    }
};

bigdesk_charts.jvmThreads = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Threads",
                series1: "Count",
                series2: "Peak",
                margin_left: 5,
                margin_bottom: 6,
                width: 60})
            .svg(element);
    },

    series1: function(stats){
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.jvm.timestamp,
                value: +snapshot.node.jvm.threads.count
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.jvm.timestamp,
                value: +snapshot.node.jvm.threads.peak_count
            }
        })
    }
};

bigdesk_charts.jvmHeapMem = {

    chart: function(element) {
        return timeAreaChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Heap Mem",
                series1: "Used",
                series2: "Committed",
                margin_left: 5,
                margin_bottom: 6,
                width: 85})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.jvm.timestamp,
                value: +snapshot.node.jvm.mem.heap_used_in_bytes
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.jvm.timestamp,
                value: +snapshot.node.jvm.mem.heap_committed_in_bytes
            }
        })
    }
};

bigdesk_charts.jvmGC = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "GC (??)",
                series1: "Young gen count",
                series2: "Old gen count",
                series3: "Time both (sec)",
                margin_left: 5,
                margin_bottom: 6,
                width: 105})
            .svg(element);
    },

	series1: function(stats) {
		return stats.map(function(snapshot){
			return {
				timestamp: +snapshot.node.jvm.timestamp,
				value: +snapshot.node.jvm.gc.collectors.young.collection_count
			}
		})
	},

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.jvm.timestamp,
                value: +snapshot.node.jvm.gc.collectors.old.collection_count
            }
        })
    },

    series3: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.jvm.timestamp,
                value: +(snapshot.node.jvm.gc.collectors.old.collection_time_in_millis + snapshot.node.jvm.gc.collectors.young.collection_time_in_millis) / 1000
            }
        })
    }
};

bigdesk_charts.threadpoolSearch = {
	chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Search",
                series1: "Count",
                series2: "Peak",
                series3: "Queue",
                margin_left: 5,
                margin_bottom: 6,
                width: 60})
            .svg(element);
    },

    series1: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.search.active
            }
        })
    },

	series2: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.search.largest
            }
        })
    },

	series3: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.search.queue
            }
        })
    }

}

bigdesk_charts.threadpoolIndex = {
	chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Index",
                series1: "Count",
                series2: "Peak",
                series3: "Queue",
                margin_left: 5,
                margin_bottom: 6,
                width: 60})
            .svg(element);
    },

    series1: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.index.active
            }
        })
    },

	series2: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.index.largest
            }
        })
    },

	series3: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.index.queue
            }
        })
    }

}

bigdesk_charts.threadpoolBulk = {
	chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Bulk",
                series1: "Count",
                series2: "Peak",
                series3: "Queue",
                margin_left: 5,
                margin_bottom: 6,
                width: 60})
            .svg(element);
    },

    series1: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.bulk.active
            }
        })
    },

	series2: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.bulk.largest
            }
        })
    },

	series3: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.bulk.queue
            }
        })
    }

}

bigdesk_charts.threadpoolRefresh = {
	chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Refresh",
                series1: "Count",
                series2: "Peak",
                series3: "Queue",
                margin_left: 5,
                margin_bottom: 6,
                width: 60})
            .svg(element);
    },

    series1: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.refresh.active
            }
        })
    },

	series2: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.refresh.largest
            }
        })
    },

	series3: function(stats){
         return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.timestamp,
                value: +snapshot.node.thread_pool.refresh.queue
            }
        })
    }

}

bigdesk_charts.osMem = {

    chart: function(element) {
        return timeAreaChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Mem",
                series1: "Used",
                series2: "Free",
                margin_left: 5,
                margin_bottom: 6,
                width: 55})
            .svg(element);
    },

    series1: function(stats) {
        return  stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.os.timestamp,
                value: +snapshot.node.os.mem.used_in_bytes
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.os.timestamp,
                value: ((+snapshot.node.os.mem.free_in_bytes) + (+snapshot.node.os.mem.used_in_bytes))
            }
        })
    }
};

bigdesk_charts.osPercent = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Resouce Used (%)",
                series1: "Cpu",
                series2: "Mem",
                series3: "100",
                margin_left: 5,
                margin_bottom: 6,
                width: 55})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.os.timestamp,
                value:
                    +snapshot.node.process.cpu.percent
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.os.timestamp,
                value:
                    +snapshot.node.os.mem.used_percent
            }
        })
    },
    series3: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.node.os.timestamp,
                value: +100
            }
        })
    }
};

bigdesk_charts.indicesSegments = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Indices Segments",
                series1: "Segments count",
                series2: "Shards count",
                margin_left: 5,
                margin_bottom: 6,
                width: 60})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.segments.count
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +0
            }
        })
    }
};

bigdesk_charts.indicesSearchReqs = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Search requests per second (??)",
                series1: "Fetch",
                series2: "Query",
                margin_left: 5,
                margin_bottom: 6,
                width: 60})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.search.fetch_total
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.search.query_total
            }
        })
    }
};

bigdesk_charts.indicesSearchTime = {

    chart: function(element) {
        return  timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Search time per second (??)",
                series1: "Fetch",
                series2: "Query",
                margin_left: 5,
                margin_bottom: 6,
                width: 60})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.search.fetch_time_in_millis
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.search.query_time_in_millis
            }
        })
    }
};

bigdesk_charts.indicesGetReqs = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Get requests per second (??)",
                series1: "Missing",
                series2: "Exists",
                series3: "Get",
                margin_left: 5,
                margin_bottom: 6,
                width: 65})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.get.total
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.get.missing_total
            }
        })
    },

    series3: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.get.exists_total
            }
        })
    }
};

bigdesk_charts.indicesGetTime = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Get time per second (??)",
                series1: "Missing",
                series2: "Exists",
                series3: "Get",
                margin_left: 5,
                margin_bottom: 6,
                width: 65})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.get.time_in_millis
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.get.missing_time_in_millis
            }
        })
    },

    series3: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.get.exists_time_in_millis
            }
        })
    }
};

bigdesk_charts.indicesIndexingReqs = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Indexing requests per second (??)",
                series1: "Index",
                series2: "Delete",
                margin_left: 5,
                margin_bottom: 6,
                width: 65})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.indexing.index_total
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.indexing.delete_total
            }
        })
    }
};

bigdesk_charts.indicesIndexingTime = {
    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Indexing time per second (??)",
                series1: "Index",
                series2: "Delete",
                margin_left: 5,
                margin_bottom: 6,
                width: 65})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.indexing.index_time_in_millis
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.indexing.delete_time_in_millis
            }
        })
    }
};

bigdesk_charts.indicesCacheSize = {
    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Cache size",
                series1: "Fielddata",
                series2: "Query",
                series3: "Request",
                margin_left: 5,
                margin_bottom: 6,
                width: 65
            })
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.fielddata.memory_size_in_bytes
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.query_cache.memory_size_in_bytes
            }
        })
    },

	series3: function(stats) {
		return stats.map(function(snapshot){
			return {
				timestamp: +snapshot.id,
				//fix es1.x 
				value: snapshot.node.indices.request_cache == undefined ? 0 :+snapshot.node.indices.request_cache.memory_size_in_bytes
			}
		})
	}
};

bigdesk_charts.indicesCacheEvictions = {
    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Cache evictions (??)",
                series1: "Fielddata",
                series2: "Query",
                series3: "Request",
                margin_left: 5,
                margin_bottom: 6,
                width: 65
            })
            .svg(element);
    },
    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.fielddata.evictions
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.indices.query_cache.evictions
            }
        })
    },
    
    series3: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: snapshot.node.indices.request_cache == undefined ? 0 :+snapshot.node.indices.request_cache.evictions
            }
        })
    }
};

bigdesk_charts.transport_txrx = {

    chart: function(element) {
        return timeSeriesChart()
            .width(bigdesk_charts.default.width).height(bigdesk_charts.default.height)
            .legend({
                caption: "Transport size (??)",
                series1: "Tx",
                series2: "Rx",
                margin_left: 5,
                margin_bottom: 6,
                width: 40})
            .svg(element);
    },

    series1: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.transport.tx_size_in_bytes
            }
        })
    },

    series2: function(stats) {
        return stats.map(function(snapshot){
            return {
                timestamp: +snapshot.id,
                value: +snapshot.node.transport.rx_size_in_bytes
            }
        })
    }
};

