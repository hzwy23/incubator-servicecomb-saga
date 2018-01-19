/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.saga.alpha.core;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.servicecomb.saga.common.EventType.SagaEndedEvent;
import static org.apache.servicecomb.saga.common.EventType.TxCompensatedEvent;
import static org.apache.servicecomb.saga.common.EventType.TxEndedEvent;
import static org.apache.servicecomb.saga.common.EventType.TxStartedEvent;

import java.lang.invoke.MethodHandles;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventScanner implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final byte[] EMPTY_PAYLOAD = new byte[0];

  private final ScheduledExecutorService scheduler;
  private final TxEventRepository eventRepository;
  private final CommandRepository commandRepository;
  private final OmegaCallback omegaCallback;
  private final int commandPollingInterval;
  private final int eventPollingInterval;

  private long nextEndedEventId;
  private long nextCompensatedEventId;

  public EventScanner(ScheduledExecutorService scheduler,
      TxEventRepository eventRepository,
      CommandRepository commandRepository,
      OmegaCallback omegaCallback,
      int commandPollingInterval,
      int eventPollingInterval) {

    this.scheduler = scheduler;
    this.eventRepository = eventRepository;
    this.commandRepository = commandRepository;
    this.omegaCallback = omegaCallback;
    this.commandPollingInterval = commandPollingInterval;
    this.eventPollingInterval = eventPollingInterval;
  }

  @Override
  public void run() {
    pollCompensationCommand(commandPollingInterval);
    pollEvents();
  }

  private void pollEvents() {
    scheduler.scheduleWithFixedDelay(
        () -> {
          saveUncompensatedEventsToCommands();
          updateCompensatedCommands();
        },
        0,
        eventPollingInterval,
        MILLISECONDS);
  }

  private void saveUncompensatedEventsToCommands() {
    eventRepository.findFirstUncompensatedEventByIdGreaterThan(nextEndedEventId, TxEndedEvent.name())
        .forEach(event -> {
          log.info("Found uncompensated event {}", event);
          nextEndedEventId = event.id();
          commandRepository.saveCompensationCommands(event.globalTxId())
              .forEach(command -> nextEndedEventId = command.id());
        });
  }

  private void updateCompensatedCommands() {
    eventRepository.findFirstCompensatedEventByIdGreaterThan(nextCompensatedEventId, TxCompensatedEvent.name())
        .ifPresent(event -> {
          log.info("Found compensated event {}", event);
          nextCompensatedEventId = event.id();
          updateCompensationStatus(event);
        });
  }

  // TODO: 2018/1/13 SagaEndedEvent may still not be the last, because some omegas may have slow network and its TxEndedEvent reached late,
  // unless we ask user to specify a name for each participant in the global TX in @Compensable
  private void updateCompensationStatus(TxEvent event) {
    commandRepository.markCommandAsDone(event.globalTxId(), event.localTxId());
    log.info("Transaction with globalTxId {} and localTxId {} was compensated",
        event.globalTxId(),
        event.localTxId());

    if (eventRepository.findTransactions(event.globalTxId(), SagaEndedEvent.name()).isEmpty()
        && commandRepository.findUncompletedCommands(event.globalTxId()).isEmpty()) {

      markGlobalTxEnd(event);
    }
  }

  private void markGlobalTxEnd(TxEvent event) {
    eventRepository.save(toSagaEndedEvent(event));
    log.info("Marked end of transaction with globalTxId {}", event.globalTxId());
  }

  private TxEvent toSagaEndedEvent(TxEvent event) {
    return new TxEvent(
        event.serviceName(),
        event.instanceId(),
        new Date(),
        event.globalTxId(),
        event.globalTxId(),
        null,
        SagaEndedEvent.name(),
        "",
        EMPTY_PAYLOAD);
  }

  private void pollCompensationCommand(int commandPollingInterval) {
    scheduler.scheduleWithFixedDelay(
        () -> commandRepository.findFirstCommandToCompensate()
            .forEach(command -> {
              log.info("Compensating transaction with globalTxId {} and localTxId {}",
                  command.globalTxId(),
                  command.localTxId());

              omegaCallback.compensate(txStartedEventOf(command));
            }),
        0,
        commandPollingInterval,
        MILLISECONDS);
  }

  private TxEvent txStartedEventOf(Command command) {
    return new TxEvent(
        command.serviceName(),
        command.instanceId(),
        command.globalTxId(),
        command.localTxId(),
        command.parentTxId(),
        TxStartedEvent.name(),
        command.compensationMethod(),
        command.payloads()
    );
  }
}