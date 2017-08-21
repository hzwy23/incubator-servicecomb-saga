/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.core.application;

import static io.servicecomb.saga.core.SagaTask.SAGA_END_TASK;
import static io.servicecomb.saga.core.SagaTask.SAGA_REQUEST_TASK;
import static io.servicecomb.saga.core.SagaTask.SAGA_START_TASK;

import io.servicecomb.saga.core.EventEnvelope;
import io.servicecomb.saga.core.EventStore;
import io.servicecomb.saga.core.PersistentStore;
import io.servicecomb.saga.core.RequestProcessTask;
import io.servicecomb.saga.core.Saga;
import io.servicecomb.saga.core.SagaEndTask;
import io.servicecomb.saga.core.SagaEvent;
import io.servicecomb.saga.core.SagaLog;
import io.servicecomb.saga.core.SagaStartTask;
import io.servicecomb.saga.core.SagaTask;
import io.servicecomb.saga.core.Transport;
import io.servicecomb.saga.core.application.interpreter.JsonRequestInterpreter;
import io.servicecomb.saga.infrastructure.EmbeddedEventStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

public class SagaCoordinator {

  private final PersistentStore persistentStore;
  private final JsonRequestInterpreter requestInterpreter;
  private final Transport transport;

  public SagaCoordinator(
      PersistentStore persistentStore,
      JsonRequestInterpreter requestInterpreter,
      Transport transport) {
    this.persistentStore = persistentStore;
    this.requestInterpreter = requestInterpreter;
    this.transport = transport;
  }

  public void run(String requestJson) {
    String sagaId = UUID.randomUUID().toString();
    EventStore sagaLog = new EmbeddedEventStore();

    Saga saga = new Saga(
        sagaLog,
        sagaTasks(sagaId, requestJson, sagaLog),
        requestInterpreter.interpret(requestJson));

    saga.run();
  }

  public void reanimate() {
    Map<String, List<EventEnvelope>> pendingSagaEvents = persistentStore.findPendingSagaEvents();

    for (Entry<String, List<EventEnvelope>> entry : pendingSagaEvents.entrySet()) {
      EventStore eventStore = new EmbeddedEventStore();
      eventStore.populate(entry.getValue());
      SagaEvent event = entry.getValue().iterator().next().event;

      Saga saga = new Saga(
          eventStore,
          sagaTasks(event.sagaId, event.json(), eventStore),
          requestInterpreter.interpret(event.json()));

      saga.play();
      saga.run();
    }
  }

  private CompositeSagaLog compositeSagaLog(SagaLog sagaLog) {
    return new CompositeSagaLog(sagaLog, persistentStore);
  }

  private Map<String, SagaTask> sagaTasks(String sagaId, String requestJson, EventStore sagaLog) {
    SagaLog compositeSagaLog = compositeSagaLog(sagaLog);

    return new HashMap<String, SagaTask>() {{
      put(SAGA_START_TASK, new SagaStartTask(sagaId, requestJson, compositeSagaLog));
      put(SAGA_REQUEST_TASK, new RequestProcessTask(sagaId, compositeSagaLog, transport));
      put(SAGA_END_TASK, new SagaEndTask(sagaId, compositeSagaLog));
    }};
  }
}
