/*
 * Copyright 2013-2014 the original author or authors.
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
package org.springframework.cloud.context.environment;

import java.util.Set;

import org.springframework.context.ApplicationEvent;
import org.springframework.core.env.Environment;

/**
 * Event published to signal a change in the {@link Environment}.
 *
 * 发布的事件表明{@link Environment}的变化。
 * 
 * @author Dave Syer
 *
 */
@SuppressWarnings("serial")
public class EnvironmentChangeEvent extends ApplicationEvent {

	private Set<String> keys;

	public EnvironmentChangeEvent(Set<String> keys) {
		// Backwards compatible constructor with less utility (practically no use at all)
		//　向后兼容的构造函数，实用程序较少（根本没用）
		this(keys, keys);
	}

	public EnvironmentChangeEvent(Object context, Set<String> keys) {
		super(context);
		this.keys = keys;
	}

	/**
	 * @return The keys.
	 */
	public Set<String> getKeys() {
		return keys;
	}

}
