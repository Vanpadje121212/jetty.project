//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Extended Server Tester.
 */
public class ExtendedServerTest extends HttpServerTestBase
{
    @BeforeEach
    public void init() throws Exception
    {
        startServer(new ServerConnector(_server, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return configure(new ExtendedHttpConnection(getHttpConfiguration(), connector, endPoint), connector, endPoint);
            }
        })
        {
            @Override
            protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
            {
                return new ExtendedEndPoint(channel, selectSet, key, getScheduler());
            }
        });
    }

    private static class ExtendedEndPoint extends SocketChannelEndPoint
    {
        private volatile long _lastSelected;

        public ExtendedEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
        {
            super(channel, selector, key, scheduler);
        }

        @Override
        public Runnable onSelected()
        {
            _lastSelected = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            return super.onSelected();
        }

        long getLastSelected()
        {
            return _lastSelected;
        }
    }

    private static class ExtendedHttpConnection extends HttpConnection
    {
        public ExtendedHttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
        {
            super(config, connector, endPoint, false);
        }

        @Override
        protected HttpChannelOverHttp newHttpChannel()
        {
            return new HttpChannelOverHttp(this, getConnector(), getHttpConfiguration(), getEndPoint(), this)
            {
                @Override
                public void startRequest(String method, String uri, HttpVersion version)
                {
                    getRequest().setAttribute("DispatchedAt", ((ExtendedEndPoint)getEndPoint()).getLastSelected());
                    super.startRequest(method, uri, version);
                }
            };
        }
    }

    @Test
    public void testExtended() throws Exception
    {
        configureServer(new DispatchedAtHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            os.write("GET / HTTP/1.0\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
            Thread.sleep(200);
            long end = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            os.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));

            // Read the response.
            String response = readResponse(client);

            assertThat(response, Matchers.containsString("HTTP/1.1 200 OK"));
            assertThat(response, Matchers.containsString("DispatchedAt="));

            String s = response.substring(response.indexOf("DispatchedAt=") + 13);
            s = s.substring(0, s.indexOf('\n'));
            long dispatched = Long.parseLong(s);

            assertThat(dispatched, Matchers.greaterThanOrEqualTo(start));
            assertThat(dispatched, Matchers.lessThan(end));
        }
    }

    protected static class DispatchedAtHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.getOutputStream().print("DispatchedAt=" + request.getAttribute("DispatchedAt") + "\r\n");
        }
    }
}
