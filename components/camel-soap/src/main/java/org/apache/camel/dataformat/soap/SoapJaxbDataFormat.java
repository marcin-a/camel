/**
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
package org.apache.camel.dataformat.soap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import javax.jws.WebMethod;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.JAXBIntrospector;
import javax.xml.namespace.QName;
import javax.xml.ws.WebFault;

import org.apache.camel.Exchange;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.component.bean.BeanInvocation;
import org.apache.camel.converter.jaxb.JaxbDataFormat;
import org.apache.camel.dataformat.soap.name.ElementNameStrategy;
import org.apache.camel.dataformat.soap.name.TypeNameStrategy;
import org.xmlsoap.schemas.soap.envelope.Body;
import org.xmlsoap.schemas.soap.envelope.Detail;
import org.xmlsoap.schemas.soap.envelope.Envelope;
import org.xmlsoap.schemas.soap.envelope.Fault;
import org.xmlsoap.schemas.soap.envelope.ObjectFactory;

/**
 * Marshaling from Objects to SOAP and back by using JAXB. The classes to be
 * processed need to have JAXB annotations. For marshaling a ElementNameStrategy
 * is used to determine how the top level elements in SOAP are named as this can
 * not be extracted from JAXB.
 */
public class SoapJaxbDataFormat extends JaxbDataFormat {
    private static final String SOAP_PACKAGE_NAME = Envelope.class.getPackage().getName();

    private static final QName FAULT_CODE_SERVER = new QName("http://www.w3.org/2003/05/soap-envelope", "Receiver");

    private ElementNameStrategy elementNameStrategy;

    private String elementNameStrategyRef;

    /**
     * Remember to set the context path when using this constructor
     */
    public SoapJaxbDataFormat() {
        super();
    }

    /**
     * Initialize with JAXB context path
     * 
     * @param contexPath
     */
    public SoapJaxbDataFormat(String contextPath) {
        super(contextPath);
    }

    /**
     * Initialize the data format. The serviceInterface is necessary to
     * determine the element name and namespace of the element inside the soap
     * body when marshaling
     * 
     * @param jaxbPackage
     *            package for JAXB context
     * @param serviceInterface
     *            webservice interface
     */
    public SoapJaxbDataFormat(String contextPath, ElementNameStrategy elementNameStrategy) {
        this(contextPath);
        this.elementNameStrategy = elementNameStrategy;
    }

    public void setElementNameStrategy(Object nameStrategy) {
        if (nameStrategy instanceof ElementNameStrategy) {
            this.elementNameStrategy = (ElementNameStrategy) nameStrategy;
        } else {
            new IllegalArgumentException("The argument for setElementNameStrategy should be subClass of "
                    + ElementNameStrategy.class.getName());
        }
    }

    protected void checkElementNameStrategy(Exchange exchange) {
        if (elementNameStrategy == null) {
            synchronized (this) {
                if (elementNameStrategy != null) {
                    return;
                } else {
                    if (elementNameStrategyRef != null) {
                        elementNameStrategy = exchange.getContext().getRegistry().lookup(elementNameStrategyRef,
                                ElementNameStrategy.class);
                    } else {
                        elementNameStrategy = new TypeNameStrategy();
                    }
                }
            }
        }
    }

    /**
     * Marshal inputObject to SOAP xml. If the exchange or message has an
     * EXCEPTION_CAUGTH property or header then instead of the object the
     * exception is marshaled.
     * 
     * To determine the name of the top level xml elment the elementNameStrategy
     * is used.
     */
    public void marshal(Exchange exchange, final Object inputObject, OutputStream stream) throws IOException {

        checkElementNameStrategy(exchange);

        String soapAction = (String) exchange.getIn().getHeader(Exchange.SOAP_ACTION);
        if (soapAction == null && inputObject instanceof BeanInvocation) {
            BeanInvocation beanInvocation = (BeanInvocation) inputObject;
            WebMethod webMethod = beanInvocation.getMethod().getAnnotation(WebMethod.class);
            if (webMethod != null && webMethod.action() != null) {
                soapAction = webMethod.action();
            }
        }
        Body body = new Body();

        Throwable exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        if (exception == null) {
            exception = exchange.getIn().getHeader(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        }
        final JAXBElement<?> content;
        if (exception != null) {
            content = createFaultFromException(exception, soapAction);
        } else {
            content = createBodyContentFromObject(inputObject, soapAction);
        }
        body.getAny().add(content);
        Envelope envelope = new Envelope();
        envelope.setBody(body);
        JAXBElement<Envelope> envelopeEl = new ObjectFactory().createEnvelope(envelope);
        super.marshal(exchange, envelopeEl, stream);
    }

    /**
     * Create body content from a non Exception object. If the inputObject is a
     * BeanInvocation the following should be considered: The first parameter
     * will be used for the SOAP body. BeanInvocations with more than one
     * parameter are not supported. So the interface should be in doc lit bare
     * style.
     * 
     * @param inputObject
     *            object to be put into the SOAP body
     * @param soapAction
     *            for name resolution
     * @param classResolver
     *            for name resolution
     * @return JAXBElement for the body content
     */
    @SuppressWarnings("unchecked")
    private JAXBElement<?> createBodyContentFromObject(final Object inputObject, String soapAction) {
        Object graph;
        if (inputObject instanceof BeanInvocation) {
            BeanInvocation bi = (BeanInvocation) inputObject;
            if (bi.getArgs().length > 1) {
                throw new RuntimeCamelException(
                        "SoapDataFormat does not work with Beaninvocations that contain more than 1 parameter");
            }
            graph = (bi.getArgs().length == 1) ? bi.getArgs()[0] : null;
        } else {
            graph = inputObject;
        }
        QName name = elementNameStrategy.findQNameForSoapActionOrType(soapAction, graph.getClass());
        return new JAXBElement(name, graph.getClass(), graph);
    }

    /**
     * Creates a SOAP fault from the exception and populates the message as well
     * as the detail. The detail object is read from the method getFaultInfo of
     * the throwable if present
     * 
     * @param exception
     * @param soapAction
     * @return SOAP fault from given Throwable
     */
    @SuppressWarnings("unchecked")
    private JAXBElement<Fault> createFaultFromException(final Throwable exception, String soapAction) {
        WebFault webFault = exception.getClass().getAnnotation(WebFault.class);
        if (webFault == null || webFault.targetNamespace() == null) {
            throw new RuntimeException("The exception " + exception.getClass().getName()
                    + " needs to have an WebFault annotation with name and targetNamespace");
        }
        QName name = new QName(webFault.targetNamespace(), webFault.name());
        Object faultObject = null;
        try {
            Method method = exception.getClass().getMethod("getFaultInfo");
            faultObject = method.invoke(exception);
        } catch (Exception e) {
            throw new RuntimeCamelException("Exception while trying to get fault details", e);
        }
        Fault fault = new Fault();
        fault.setFaultcode(FAULT_CODE_SERVER);
        fault.setFaultstring(exception.getMessage());
        Detail detailEl = new ObjectFactory().createDetail();
        JAXBElement<?> faultDetailContent = new JAXBElement(name, faultObject.getClass(), faultObject);
        detailEl.getAny().add(faultDetailContent);
        fault.setDetail(detailEl);
        return new ObjectFactory().createFault(fault);
    }

    /**
     * Unmarshal a given SOAP xml stream and return the content of the SOAP body
     */
    public Object unmarshal(Exchange exchange, InputStream stream) throws IOException {
        Object rootObject = JAXBIntrospector.getValue(super.unmarshal(exchange, stream));
        if (rootObject.getClass() != Envelope.class) {
            throw new RuntimeCamelException("Expected Soap Envelope but got " + rootObject.getClass());
        }
        Envelope envelope = (Envelope) rootObject;
        Object payloadEl = envelope.getBody().getAny().get(0);
        Object payload = JAXBIntrospector.getValue(payloadEl);
        if (payload instanceof Fault) {
            Exception exception = createExceptionFromFault((Fault) payload);
            exchange.setException(exception);
            return null;
        } else {
            return isIgnoreJAXBElement() ? payload : payloadEl;
        }
    }

    /**
     * Creates an exception and eventually an embedded bean that contains the
     * fault detail. The exception class is determined by using the
     * elementNameStrategy. The qName of the fault detail should match the
     * WebFault annotation of the Exception class. If no fault detail is set the
     * a RuntimeCamelException is created.
     * 
     * @param fault
     *            Soap fault
     * @return created Exception
     */
    private Exception createExceptionFromFault(Fault fault) {
        List<Object> detailList = fault.getDetail().getAny();
        String message = fault.getFaultstring();

        if (detailList.size() == 0) {
            return new RuntimeCamelException(message);
        }
        JAXBElement<?> detailEl = (JAXBElement<?>) detailList.get(0);
        Class<? extends Exception> exceptionClass = elementNameStrategy.findExceptionForFaultName(detailEl.getName());
        Constructor<? extends Exception> messageContructor;
        Constructor<? extends Exception> constructor;

        try {
            messageContructor = exceptionClass.getConstructor(String.class);
            Object detail = JAXBIntrospector.getValue(detailEl);
            try {
                constructor = exceptionClass.getConstructor(String.class, detail.getClass());
                return constructor.newInstance(message, detail);
            } catch (NoSuchMethodException e) {
                return messageContructor.newInstance(message);
            }
        } catch (Exception e) {
            throw new RuntimeCamelException(e);
        }
    }

    /**
     * Added the generated SOAP package to the JAXB context so Soap datatypes
     * are available
     */
    @Override
    protected JAXBContext createContext() throws JAXBException {
        if (getContextPath() != null) {
            return JAXBContext.newInstance(SOAP_PACKAGE_NAME + ":" + getContextPath());
        } else {
            return JAXBContext.newInstance();
        }
    }

    public void setElementNameStrategy(ElementNameStrategy elementNameStrategy) {
        this.elementNameStrategy = elementNameStrategy;
    }

}
