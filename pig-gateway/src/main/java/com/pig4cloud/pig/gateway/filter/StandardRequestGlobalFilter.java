/*
 * Copyright (c) 2020 pig4cloud Authors. All Rights Reserved.
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

package com.pig4cloud.pig.gateway.filter;

import com.pig4cloud.pig.common.core.constant.CommonConstants;
import com.pig4cloud.pig.common.core.constant.SecurityConstants;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.addOriginalRequestUrl;

/**
 * 一个更通用的网关请求预处理过滤器示例（标准化命名与职责）：
 * <ul>
 *     <li>清洗不可信/不应透传的请求头</li>
 *     <li>注入链路元数据（请求开始时间、请求ID）</li>
 *     <li>按网关路由约定统一去掉首段路径（等价 StripPrefix=1）</li>
 * </ul>
 *
 * 注意：该类默认不会自动生效，需要在配置类里显式注册 Bean。
 */
public class StandardRequestGlobalFilter implements GlobalFilter, Ordered {

	/**
	 * 过滤顺序建议：放在前置过滤链较靠前位置，保证后续过滤器拿到已标准化请求。
	 */
	public static final int ORDER = -100;

	private static final String TRACE_ID_HEADER = "X-Trace-Id";
	private static final String REQUEST_ID_HEADER = "X-Request-Id";
	private static final String USER_ID_HEADER = "X-User-Id";
	private static final String USERNAME_HEADER = "X-Username";

	private static final int TRACE_ID_MAX_LENGTH = 64;
	private static final Pattern SAFE_TRACE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]{8,64}$");

	/**
	 * RFC 7230 中不应被代理转发的 hop-by-hop 头。
	 */
	private static final List<String> HOP_BY_HOP_HEADERS = Arrays.asList(
			HttpHeaders.CONNECTION,
			"Keep-Alive",
			HttpHeaders.PROXY_AUTHENTICATE,
			HttpHeaders.PROXY_AUTHORIZATION,
			HttpHeaders.TE,
			HttpHeaders.TRAILER,
			HttpHeaders.TRANSFER_ENCODING,
			HttpHeaders.UPGRADE
	);

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		ServerHttpRequest sanitizedRequest = sanitizeAndEnrichRequest(exchange.getRequest());
		ServerHttpRequest normalizedRequest = normalizePath(exchange, sanitizedRequest);
		return chain.filter(exchange.mutate().request(normalizedRequest).build());
	}

	private ServerHttpRequest sanitizeAndEnrichRequest(ServerHttpRequest request) {
		return request.mutate().headers(headers -> {
			// 1) 防止外部请求伪造内部调用标记
			headers.remove(SecurityConstants.FROM);

			// 2) 清理外部可伪造的用户身份头，后续应由认证通过后重建
			headers.remove(USER_ID_HEADER);
			headers.remove(USERNAME_HEADER);

			// 3) 清理不应透传的 hop-by-hop 头，减少协议层问题
			HOP_BY_HOP_HEADERS.forEach(headers::remove);

			// 4) 注入统一起始时间，便于耗时统计
			headers.set(CommonConstants.REQUEST_START_TIME, String.valueOf(System.currentTimeMillis()));

			// 5) TraceId 做安全规范化：合法则沿用，非法/缺失则重建
			String traceId = normalizeTraceId(headers.getFirst(TRACE_ID_HEADER));
			headers.set(TRACE_ID_HEADER, traceId);

			// 6) RequestId 用于应用日志追踪（未传则回退为 TraceId）
			if (!StringUtils.hasText(headers.getFirst(REQUEST_ID_HEADER))) {
				headers.set(REQUEST_ID_HEADER, traceId);
			}
		}).build();
	}

	private String normalizeTraceId(String incomingTraceId) {
		if (!StringUtils.hasText(incomingTraceId)) {
			return generateTraceId();
		}

		String candidate = incomingTraceId.trim();
		if (candidate.length() > TRACE_ID_MAX_LENGTH) {
			return generateTraceId();
		}

		if (!SAFE_TRACE_ID_PATTERN.matcher(candidate).matches()) {
			return generateTraceId();
		}

		return candidate;
	}

	private String generateTraceId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private ServerHttpRequest normalizePath(ServerWebExchange exchange, ServerHttpRequest request) {
		addOriginalRequestUrl(exchange, request.getURI());

		String rawPath = request.getURI().getRawPath();
		String normalizedPath = stripFirstPathSegment(rawPath);
		ServerHttpRequest mutatedRequest = request.mutate().path(normalizedPath).build();
		exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, mutatedRequest.getURI());
		return mutatedRequest;
	}

	/**
	 * 将 "/service/api/v1" 转换为 "/api/v1"，空路径或单段路径回退为 "/"。
	 */
	private String stripFirstPathSegment(String rawPath) {
		String[] segments = StringUtils.tokenizeToStringArray(rawPath, "/");
		if (segments.length <= 1) {
			return "/";
		}
		return "/" + Arrays.stream(segments).skip(1L).collect(Collectors.joining("/"));
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

}
