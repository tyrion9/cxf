/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.systest.jaxws.tracing.opentracing;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.xml.ws.soap.SOAPFaultException;

import com.uber.jaeger.Configuration;
import com.uber.jaeger.SpanContext;
import com.uber.jaeger.samplers.ConstSampler;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.ext.logging.LoggingInInterceptor;
import org.apache.cxf.ext.logging.LoggingOutInterceptor;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.apache.cxf.message.Message;
import org.apache.cxf.systest.jaeger.TestSender;
import org.apache.cxf.systest.jaxws.tracing.BookStoreService;
import org.apache.cxf.testutil.common.AbstractBusClientServerTestBase;
import org.apache.cxf.testutil.common.AbstractBusTestServerBase;
import org.apache.cxf.tracing.opentracing.OpenTracingClientFeature;
import org.apache.cxf.tracing.opentracing.OpenTracingFeature;
import org.apache.cxf.tracing.opentracing.internal.TextMapInjectAdapter;
import org.awaitility.Duration;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.empty;

import io.opentracing.ActiveSpan;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format.Builtin;
import io.opentracing.util.GlobalTracer;

public class OpenTracingTracingTest extends AbstractBusClientServerTestBase {
    public static final String PORT = allocatePort(OpenTracingTracingTest.class);

    private Tracer tracer;
    private Random random;
    
    @Ignore
    public static class Server extends AbstractBusTestServerBase {
        protected void run() {
            final Tracer tracer = new Configuration("book-store", 
                    new Configuration.SamplerConfiguration(ConstSampler.TYPE, 1),
                    new Configuration.ReporterConfiguration(new TestSender())
                ).getTracer();
            GlobalTracer.register(tracer);
            
            final JaxWsServerFactoryBean sf = new JaxWsServerFactoryBean();
            sf.setServiceClass(BookStore.class);
            sf.setAddress("http://localhost:" + PORT);
            sf.getFeatures().add(new OpenTracingFeature(tracer));
            sf.create();
        }
    }

    private interface Configurator {
        void configure(JaxWsProxyFactoryBean factory);
    }

    @BeforeClass
    public static void startServers() throws Exception {
        //keep out of process due to stack traces testing failures
        assertTrue("server did not launch correctly", launchServer(Server.class, true));
        createStaticBus();
    }

    @Before
    public void setUp() {
        random = new Random();
        
        tracer = new Configuration("tracer", 
                new Configuration.SamplerConfiguration(ConstSampler.TYPE, 1),
                new Configuration.ReporterConfiguration(new TestSender())
            ).getTracer();

        TestSender.clear();
    }

    @Test
    public void testThatNewSpanIsCreatedWhenNotProvided() throws MalformedURLException {
        final BookStoreService service = createJaxWsService();
        assertThat(service.getBooks().size(), equalTo(2));

        assertThat(TestSender.getAllSpans().size(), equalTo(2));
        assertThat(TestSender.getAllSpans().get(0).getOperationName(), equalTo("Get Books"));
        assertThat(TestSender.getAllSpans().get(1).getOperationName(), equalTo("POST /BookStore"));
    }

    @Test
    public void testThatNewInnerSpanIsCreated() throws MalformedURLException {
        final SpanContext spanId = fromRandom();

        final Map<String, List<String>> headers = new HashMap<>();
        tracer.inject(spanId, Builtin.HTTP_HEADERS, new TextMapInjectAdapter(headers));

        final BookStoreService service = createJaxWsService(headers);
        assertThat(service.getBooks().size(), equalTo(2));

        assertThat(TestSender.getAllSpans().size(), equalTo(2));
        assertThat(TestSender.getAllSpans().get(0).getOperationName(), equalTo("Get Books"));
        assertThat(TestSender.getAllSpans().get(1).getOperationName(), equalTo("POST /BookStore"));
    }

    @Test
    public void testThatNewChildSpanIsCreatedWhenParentIsProvided() throws MalformedURLException {
        final BookStoreService service = createJaxWsService(new Configurator() {
            @Override
            public void configure(final JaxWsProxyFactoryBean factory) {
                factory.getFeatures().add(new OpenTracingClientFeature(tracer));
            }
        });
        assertThat(service.getBooks().size(), equalTo(2));

        assertThat(TestSender.getAllSpans().size(), equalTo(3));
        assertThat(TestSender.getAllSpans().get(0).getOperationName(), equalTo("Get Books"));
        assertThat(TestSender.getAllSpans().get(0).getReferences(), not(empty()));
        assertThat(TestSender.getAllSpans().get(1).getOperationName(), equalTo("POST /BookStore"));
        assertThat(TestSender.getAllSpans().get(2).getOperationName(),
            equalTo("POST http://localhost:" + PORT + "/BookStore"));
    }

    @Test
    public void testThatProvidedSpanIsNotClosedWhenActive() throws MalformedURLException {
        final BookStoreService service = createJaxWsService(new Configurator() {
            @Override
            public void configure(final JaxWsProxyFactoryBean factory) {
                factory.getFeatures().add(new OpenTracingClientFeature(tracer));
            }
        });

        try (ActiveSpan scope = tracer.buildSpan("test span").startActive()) {
            assertThat(service.getBooks().size(), equalTo(2));
            assertThat(tracer.activeSpan(), not(nullValue()));

            assertThat(TestSender.getAllSpans().size(), equalTo(2));
            assertThat(TestSender.getAllSpans().get(0).getOperationName(), equalTo("Get Books"));
            assertThat(TestSender.getAllSpans().get(0).getReferences(), not(empty()));
            assertThat(TestSender.getAllSpans().get(1).getOperationName(), equalTo("POST /BookStore"));
        }

        // Await till flush happens, usually every second
        await().atMost(Duration.ONE_SECOND).until(()-> TestSender.getAllSpans().size() == 3);

        assertThat(TestSender.getAllSpans().size(), equalTo(3));
        assertThat(TestSender.getAllSpans().get(2).getOperationName(), equalTo("test span"));
    }

    @Test
    public void testThatNewSpanIsCreatedInCaseOfFault() throws MalformedURLException {
        final BookStoreService service = createJaxWsService();

        try {
            service.removeBooks();
            fail("Expected SOAPFaultException to be raised");
        } catch (final SOAPFaultException ex) {
            /* expected exception */
        }

        assertThat(TestSender.getAllSpans().size(), equalTo(1));
        assertThat(TestSender.getAllSpans().get(0).getOperationName(), equalTo("POST /BookStore"));
    }

    @Test
    public void testThatNewChildSpanIsCreatedWhenParentIsProvidedInCaseOfFault() throws MalformedURLException {
        final BookStoreService service = createJaxWsService(new Configurator() {
            @Override
            public void configure(final JaxWsProxyFactoryBean factory) {
                factory.getFeatures().add(new OpenTracingClientFeature(tracer));
                factory.getOutInterceptors().add(new LoggingOutInterceptor());
                factory.getInInterceptors().add(new LoggingInInterceptor());
            }
        });

        try {
            service.removeBooks();
            fail("Expected SOAPFaultException to be raised");
        } catch (final SOAPFaultException ex) {
            /* expected exception */
        }

        assertThat(TestSender.getAllSpans().size(), equalTo(2));
        assertThat(TestSender.getAllSpans().get(0).getOperationName(), equalTo("POST /BookStore"));
        assertThat(TestSender.getAllSpans().get(1).getOperationName(),
            equalTo("POST http://localhost:" + PORT + "/BookStore"));
    }

    private BookStoreService createJaxWsService() throws MalformedURLException {
        return createJaxWsService(new HashMap<String, List<String>>());
    }

    private BookStoreService createJaxWsService(final Map<String, List<String>> headers) throws MalformedURLException {
        return createJaxWsService(headers, null);
    }

    private BookStoreService createJaxWsService(final Configurator configurator) throws MalformedURLException {
        return createJaxWsService(new HashMap<String, List<String>>(), configurator);
    }

    private BookStoreService createJaxWsService(final Map<String, List<String>> headers,
            final Configurator configurator) throws MalformedURLException {

        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
        factory.getOutInterceptors().add(new LoggingOutInterceptor());
        factory.getInInterceptors().add(new LoggingInInterceptor());
        factory.setServiceClass(BookStoreService.class);
        factory.setAddress("http://localhost:" + PORT + "/BookStore");

        if (configurator != null) {
            configurator.configure(factory);
        }

        final BookStoreService service = (BookStoreService) factory.create();
        final Client proxy = ClientProxy.getClient(service);
        proxy.getRequestContext().put(Message.PROTOCOL_HEADERS, headers);

        return service;
    }

    private SpanContext fromRandom() {
        return new SpanContext(random.nextLong(), /* traceId */ random.nextLong() /* spanId */, 
            random.nextLong() /* parentId */, (byte)1 /* sampled */);
    }
}
