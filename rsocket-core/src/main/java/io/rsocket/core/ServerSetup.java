/*
 * Copyright 2015-2020 the original author or authors.
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

package io.rsocket.core;

import static io.rsocket.keepalive.KeepAliveHandler.*;

import io.netty.buffer.ByteBuf;
import io.rsocket.DuplexConnection;
import io.rsocket.exceptions.RejectedResumeException;
import io.rsocket.exceptions.UnsupportedSetupException;
import io.rsocket.frame.ErrorFrameCodec;
import io.rsocket.frame.ResumeFrameCodec;
import io.rsocket.frame.SetupFrameCodec;
import io.rsocket.internal.ClientServerInputMultiplexer;
import io.rsocket.keepalive.KeepAliveHandler;
import io.rsocket.resume.*;
import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

abstract class ServerSetup {

  abstract Mono<Void> acceptRSocketSetup(
      ByteBuf frame,
      ClientServerInputMultiplexer multiplexer,
      BiFunction<KeepAliveHandler, ClientServerInputMultiplexer, Mono<Void>> then);

  abstract Mono<Void> acceptRSocketResume(ByteBuf frame, ClientServerInputMultiplexer multiplexer);

  void dispose() {}

  Mono<Void> sendError(ClientServerInputMultiplexer multiplexer, Exception exception) {
    DuplexConnection duplexConnection = multiplexer.asSetupConnection();
    return duplexConnection
        .sendOne(ErrorFrameCodec.encode(duplexConnection.alloc(), 0, exception))
        .onErrorResume(err -> Mono.empty());
  }

  static class DefaultServerSetup extends ServerSetup {

    @Override
    public Mono<Void> acceptRSocketSetup(
        ByteBuf frame,
        ClientServerInputMultiplexer multiplexer,
        BiFunction<KeepAliveHandler, ClientServerInputMultiplexer, Mono<Void>> then) {

      if (SetupFrameCodec.resumeEnabled(frame)) {
        return sendError(multiplexer, new UnsupportedSetupException("resume not supported"))
            .doFinally(
                signalType -> {
                  frame.release();
                  multiplexer.dispose();
                });
      } else {
        return then.apply(new DefaultKeepAliveHandler(multiplexer), multiplexer);
      }
    }

    @Override
    public Mono<Void> acceptRSocketResume(ByteBuf frame, ClientServerInputMultiplexer multiplexer) {

      return sendError(multiplexer, new RejectedResumeException("resume not supported"))
          .doFinally(
              signalType -> {
                frame.release();
                multiplexer.dispose();
              });
    }
  }

  static class ResumableServerSetup extends ServerSetup {
    private final SessionManager sessionManager;
    private final Duration resumeSessionDuration;
    private final Duration resumeStreamTimeout;
    private final Function<? super ByteBuf, ? extends ResumableFramesStore> resumeStoreFactory;
    private final boolean cleanupStoreOnKeepAlive;

    ResumableServerSetup(
        SessionManager sessionManager,
        Duration resumeSessionDuration,
        Duration resumeStreamTimeout,
        Function<? super ByteBuf, ? extends ResumableFramesStore> resumeStoreFactory,
        boolean cleanupStoreOnKeepAlive) {
      this.sessionManager = sessionManager;
      this.resumeSessionDuration = resumeSessionDuration;
      this.resumeStreamTimeout = resumeStreamTimeout;
      this.resumeStoreFactory = resumeStoreFactory;
      this.cleanupStoreOnKeepAlive = cleanupStoreOnKeepAlive;
    }

    @Override
    public Mono<Void> acceptRSocketSetup(
        ByteBuf frame,
        ClientServerInputMultiplexer multiplexer,
        BiFunction<KeepAliveHandler, ClientServerInputMultiplexer, Mono<Void>> then) {

      if (SetupFrameCodec.resumeEnabled(frame)) {
        ByteBuf resumeToken = SetupFrameCodec.resumeToken(frame);

        ResumableDuplexConnection connection =
            sessionManager
                .save(
                    new ServerRSocketSession(
                        multiplexer.asClientServerConnection(),
                        resumeSessionDuration,
                        resumeStreamTimeout,
                        resumeStoreFactory,
                        resumeToken,
                        cleanupStoreOnKeepAlive))
                .resumableConnection();
        return then.apply(
            new ResumableKeepAliveHandler(connection),
            new ClientServerInputMultiplexer(connection));
      } else {
        return then.apply(new DefaultKeepAliveHandler(multiplexer), multiplexer);
      }
    }

    @Override
    public Mono<Void> acceptRSocketResume(ByteBuf frame, ClientServerInputMultiplexer multiplexer) {
      ServerRSocketSession session = sessionManager.get(ResumeFrameCodec.token(frame));
      if (session != null) {
        return session
            .continueWith(multiplexer.asClientServerConnection())
            .resumeWith(frame)
            .onClose()
            .then();
      } else {
        return sendError(multiplexer, new RejectedResumeException("unknown resume token"))
            .doFinally(
                s -> {
                  frame.release();
                  multiplexer.dispose();
                });
      }
    }

    @Override
    public void dispose() {
      sessionManager.dispose();
    }
  }
}
