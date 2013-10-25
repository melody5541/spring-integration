/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.config.xml;

import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.AbstractSimpleBeanDefinitionParser;
import org.springframework.integration.gateway.GatewayProxyFactoryBean;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;

/**
 * Parser for the &lt;gateway/&gt; element.
 *
 * @author Mark Fisher
 * @author Oleg Zhurakousky
 * @author Gary Russell
 */
public class GatewayParser extends AbstractSimpleBeanDefinitionParser {

	private static String[] referenceAttributes = new String[] {
		"default-request-channel", "default-reply-channel", "error-channel", "message-mapper", "async-executor", "mapper"
	};

	private static String[] innerAttributes = new String[] {
		"request-channel", "reply-channel", "request-timeout", "reply-timeout", "error-channel"
	};


	@Override
	protected String getBeanClassName(Element element) {
		return GatewayProxyFactoryBean.class.getName();
	}

	@Override
	protected boolean shouldGenerateIdAsFallback() {
		return true;
	}

	@Override
	protected boolean isEligibleAttribute(String attributeName) {
		return !ObjectUtils.containsElement(referenceAttributes, attributeName)
				&& !ObjectUtils.containsElement(innerAttributes, attributeName)
				&& !("default-payload-expression".equals(attributeName))
				&& super.isEligibleAttribute(attributeName);
	}

	@Override
	protected void postProcess(BeanDefinitionBuilder builder, Element element) {
		if ("chain".equals(element.getParentNode().getLocalName())) {
			this.postProcessInnerGateway(builder, element);
		}
		else {
			this.postProcessGateway(builder, element);
		}
	}

	private void postProcessInnerGateway(BeanDefinitionBuilder builder, Element element) {
		IntegrationNamespaceUtils.setReferenceIfAttributeDefined(builder, element, "request-channel", "defaultRequestChannel");
		IntegrationNamespaceUtils.setReferenceIfAttributeDefined(builder, element, "reply-channel", "defaultReplyChannel");
		IntegrationNamespaceUtils.setValueIfAttributeDefined(builder, element, "request-timeout", "defaultRequestTimeout");
		IntegrationNamespaceUtils.setValueIfAttributeDefined(builder, element, "reply-timeout", "defaultReplyTimeout");
		IntegrationNamespaceUtils.setReferenceIfAttributeDefined(builder, element, "error-channel");
		IntegrationNamespaceUtils.setReferenceIfAttributeDefined(builder, element, "async-executor");
	}

	private void postProcessGateway(BeanDefinitionBuilder builder, Element element) {
		for (String attributeName : referenceAttributes) {
			IntegrationNamespaceUtils.setReferenceIfAttributeDefined(builder, element, attributeName);
		}

		boolean hasMapper = StringUtils.hasText(element.getAttribute("mapper"));
		boolean hasDefaultPayloadExpression = StringUtils.hasText(element.getAttribute("default-payload-expression"));
		Assert.state(hasMapper ? !hasDefaultPayloadExpression : true, "'default-payload-expression' is not allowed when a 'mapper' is provided");

		List<Element> invocationHeaders = DomUtils.getChildElementsByTagName(element, "default-header");
		boolean hasDefaultHeaders = !CollectionUtils.isEmpty(invocationHeaders);

		Assert.state(hasMapper ? !hasDefaultHeaders : true, "default-header elements are not allowed when a 'mapper' is provided");

		if (hasDefaultHeaders || hasDefaultPayloadExpression) {
			BeanDefinitionBuilder methodMetadataBuilder = BeanDefinitionBuilder.genericBeanDefinition(
					"org.springframework.integration.gateway.GatewayMethodMetadata");
			this.setMethodInvocationHeaders(methodMetadataBuilder, invocationHeaders);
			IntegrationNamespaceUtils.setValueIfAttributeDefined(methodMetadataBuilder, element,
					"default-payload-expression", "payloadExpression");
			builder.addPropertyValue("globalMethodMetadata", methodMetadataBuilder.getBeanDefinition());
		}

		List<Element> elements = DomUtils.getChildElementsByTagName(element, "method");
		ManagedMap<String, BeanDefinition> methodMetadataMap = null;
		if (elements != null && elements.size() > 0) {
			methodMetadataMap = new ManagedMap<String, BeanDefinition>();
		}
		for (Element methodElement : elements) {
			String methodName = methodElement.getAttribute("name");
			BeanDefinitionBuilder methodMetadataBuilder = BeanDefinitionBuilder.genericBeanDefinition(
					"org.springframework.integration.gateway.GatewayMethodMetadata");
			methodMetadataBuilder.addPropertyValue("requestChannelName", methodElement.getAttribute("request-channel"));
			methodMetadataBuilder.addPropertyValue("replyChannelName", methodElement.getAttribute("reply-channel"));
			methodMetadataBuilder.addPropertyValue("requestTimeout", methodElement.getAttribute("request-timeout"));
			methodMetadataBuilder.addPropertyValue("replyTimeout", methodElement.getAttribute("reply-timeout"));
			IntegrationNamespaceUtils.setValueIfAttributeDefined(methodMetadataBuilder, methodElement, "payload-expression");
			Assert.state(hasMapper ? !StringUtils.hasText(element.getAttribute("payload-expression")) : true,
						"'payload-expression' is not allowed when a 'mapper' is provided");
			invocationHeaders = DomUtils.getChildElementsByTagName(methodElement, "header");
			if (!CollectionUtils.isEmpty(invocationHeaders)) {
				Assert.state(!hasMapper, "header elements are not allowed when a 'mapper' is provided");
				this.setMethodInvocationHeaders(methodMetadataBuilder, invocationHeaders);
			}
			methodMetadataMap.put(methodName, methodMetadataBuilder.getBeanDefinition());
		}
		builder.addPropertyValue("methodMetadataMap", methodMetadataMap);
	}

	private void setMethodInvocationHeaders(BeanDefinitionBuilder gatewayDefinitionBuilder, List<Element> invocationHeaders) {
		Map<String, Object> headerExpressions = new ManagedMap<String, Object>();
		for (Element headerElement : invocationHeaders) {
			String headerName = headerElement.getAttribute("name");
			String headerValue = headerElement.getAttribute("value");
			String headerExpression = headerElement.getAttribute("expression");
			boolean hasValue = StringUtils.hasText(headerValue);
			boolean hasExpression = StringUtils.hasText(headerExpression);
			if (!(hasValue ^ hasExpression)) {
				throw new BeanDefinitionStoreException("exactly one of 'value' or 'expression' is required on a header sub-element");
			}
			RootBeanDefinition expressionDef = null;
			if (hasValue) {
				expressionDef = new RootBeanDefinition("org.springframework.expression.common.LiteralExpression");
				expressionDef.getConstructorArgumentValues().addGenericArgumentValue(headerValue);
			}
			else if (hasExpression) {
				expressionDef = new RootBeanDefinition("org.springframework.integration.config.ExpressionFactoryBean");
				expressionDef.getConstructorArgumentValues().addGenericArgumentValue(headerExpression);
			}
			if (expressionDef != null) {
				headerExpressions.put(headerName, expressionDef);
			}
		}
		gatewayDefinitionBuilder.addPropertyValue("headerExpressions", headerExpressions);
	}

}
