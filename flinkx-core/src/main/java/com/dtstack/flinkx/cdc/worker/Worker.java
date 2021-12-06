/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.dtstack.flinkx.cdc.worker;

import com.dtstack.flinkx.cdc.QueuesChamberlain;
import com.dtstack.flinkx.element.ColumnRowData;

import org.apache.flink.table.data.RowData;
import org.apache.flink.util.Collector;

import java.util.Deque;

/**
 * 下发数据队列中的dml数据，在遇到ddl数据之后，将数据队列的状态置为"block"
 *
 * @author tiezhu@dtstack.com
 * @since 2021/12/1 星期三
 */
public class Worker implements Runnable {

    private final QueuesChamberlain queuesChamberlain;

    private final Collector<RowData> out;

    /** 表标识 */
    private final String tableIdentity;

    /** 队列遍历深度，避免某队列长时间占用线程 */
    private final int size;

    public Worker(
            QueuesChamberlain queuesChamberlain,
            int size,
            Collector<RowData> out,
            String tableIdentity) {
        this.queuesChamberlain = queuesChamberlain;
        this.size = size;
        this.out = out;
        this.tableIdentity = tableIdentity;
    }

    @Override
    public void run() {
        send();
    }

    /** 发送数据 */
    private void send() {
        Deque<RowData> queue = queuesChamberlain.getQueueFromUnblockQueues(tableIdentity);
        for (int i = 0; i < size; i++) {
            RowData data = queue.peek();
            if (data == null) {
                break;
            }

            if (data instanceof ColumnRowData) {
                dealDML(queue);
            } else {
                queuesChamberlain.dealDdlRowData(tableIdentity, queue);
            }
        }
    }

    private void dealDML(Deque<RowData> queue) {
        // 队列头节点是dml, 将该dml数据发送到sink
        RowData rowData = queue.poll();
        out.collect(rowData);
    }
}
