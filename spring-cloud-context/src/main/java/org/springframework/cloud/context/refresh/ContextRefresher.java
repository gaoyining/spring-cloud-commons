package org.springframework.cloud.context.refresh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.Banner.Mode;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.cloud.bootstrap.BootstrapApplicationListener;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.context.support.StandardServletEnvironment;

/**
 * @author Dave Syer
 * @author Venil Noronha
 */
public class ContextRefresher {

	private static final String REFRESH_ARGS_PROPERTY_SOURCE = "refreshArgs";

	private static final String[] DEFAULT_PROPERTY_SOURCES = new String[] {
			// order matters, if cli args aren't first, things get messy
			CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
			"defaultProperties" };

	private Set<String> standardSources = new HashSet<>(
			Arrays.asList(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
					StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.JNDI_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.SERVLET_CONFIG_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.SERVLET_CONTEXT_PROPERTY_SOURCE_NAME, 
					"configurationProperties"));

	private ConfigurableApplicationContext context;
	private RefreshScope scope;

	public ContextRefresher(ConfigurableApplicationContext context, RefreshScope scope) {
		this.context = context;
		this.scope = scope;
	}

	protected ConfigurableApplicationContext getContext() {
		return this.context;
	}

	protected RefreshScope getScope() {
		return this.scope;
	}

	// 关键方法，刷新
	public synchronized Set<String> refresh() {
		// 关键方法，刷新环境变量
		Set<String> keys = refreshEnvironment();
		this.scope.refreshAll();
		return keys;
	}

	public synchronized Set<String> refreshEnvironment() {
		// 提取标准参数(SYSTEM,JNDI,SERVLET)之外所有参数变量
		Map<String, Object> before = extract(
				this.context.getEnvironment().getPropertySources());
		// 把原来的Environment里的参数放到一个新建的Spring Context容器下重新加载，完事之后关闭新容器
		addConfigFilesToEnvironment();
		// 获得最终的参数
		Set<String> keys = changes(before,
				// 把新的参数提取出来
				extract(this.context.getEnvironment().getPropertySources())).keySet();
		// 发布环境变更事件
		this.context.publishEvent(new EnvironmentChangeEvent(context, keys));
		return keys;
	}

	/* For testing. */ ConfigurableApplicationContext addConfigFilesToEnvironment() {
		ConfigurableApplicationContext capture = null;
		try {
			// 复制一个environment
			StandardEnvironment environment = copyEnvironment(
					this.context.getEnvironment());
			// 构建一个新的SpringApplicationBuilder
			SpringApplicationBuilder builder = new SpringApplicationBuilder(Empty.class)
					.bannerMode(Mode.OFF).web(WebApplicationType.NONE)
					.environment(environment);
			// Just the listeners that affect the environment (e.g. excluding logging
			// listener because it has side effects)
			// 只是影响环境的监听器（例如排除日志记录）
			// 听众，因为它有副作用）
			builder.application()
					.setListeners(Arrays.asList(new BootstrapApplicationListener(),
							new ConfigFileApplicationListener()));
			// 执行SpringApplicationBuilder
			capture = builder.run();
			// 如果包含refreshArgs，移除
			if (environment.getPropertySources().contains(REFRESH_ARGS_PROPERTY_SOURCE)) {
				environment.getPropertySources().remove(REFRESH_ARGS_PROPERTY_SOURCE);
			}
			MutablePropertySources target = this.context.getEnvironment()
					.getPropertySources();
			String targetName = null;
			for (PropertySource<?> source : environment.getPropertySources()) {
				String name = source.getName();
				if (target.contains(name)) {
					targetName = name;
				}
				if (!this.standardSources.contains(name)) {
					if (target.contains(name)) {
						target.replace(name, source);
					}
					else {
						if (targetName != null) {
							target.addAfter(targetName, source);
						}
						else {
							// targetName was null so we are at the start of the list
							target.addFirst(source);
							targetName = name;
						}
					}
				}
			}
		}
		finally {
			// 最终关闭新容器
			ConfigurableApplicationContext closeable = capture;
			while (closeable != null) {
				try {
					closeable.close();
				}
				catch (Exception e) {
					// Ignore;
				}
				if (closeable.getParent() instanceof ConfigurableApplicationContext) {
					closeable = (ConfigurableApplicationContext) closeable.getParent();
				}
				else {
					break;
				}
			}
		}
		return capture;
	}

	// Don't use ConfigurableEnvironment.merge() in case there are clashes with property
	// source names
	// 如果与属性发生冲突，请不要使用ConfigurableEnvironment.merge（）
	// 源名称
	private StandardEnvironment copyEnvironment(ConfigurableEnvironment input) {
		StandardEnvironment environment = new StandardEnvironment();
		MutablePropertySources capturedPropertySources = environment.getPropertySources();
		// Only copy the default property source(s) and the profiles over from the main
		// environment (everything else should be pristine, just like it was on startup).
		// 仅从main复制默认属性源和配置文件
		// 环境（其他一切都应该是原始的，就像它在启动时一样）。
		for (String name : DEFAULT_PROPERTY_SOURCES) {
			if (input.getPropertySources().contains(name)) {
				if (capturedPropertySources.contains(name)) {
					capturedPropertySources.replace(name,
							input.getPropertySources().get(name));
				}
				else {
					capturedPropertySources.addLast(input.getPropertySources().get(name));
				}
			}
		}
		environment.setActiveProfiles(input.getActiveProfiles());
		environment.setDefaultProfiles(input.getDefaultProfiles());
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("spring.jmx.enabled", false);
		map.put("spring.main.sources", "");
		capturedPropertySources
				.addFirst(new MapPropertySource(REFRESH_ARGS_PROPERTY_SOURCE, map));
		return environment;
	}

	private Map<String, Object> changes(Map<String, Object> before,
			Map<String, Object> after) {
		Map<String, Object> result = new HashMap<String, Object>();
		// 遍历老的
		for (String key : before.keySet()) {
			// 如果老的里面没有，把值设置为null
			if (!after.containsKey(key)) {
				result.put(key, null);
			}
			// 如果不相同，用新的值
			else if (!equal(before.get(key), after.get(key))) {
				result.put(key, after.get(key));
			}
		}
		// 遍历新的，如果老的没有，则加入
		for (String key : after.keySet()) {
			if (!before.containsKey(key)) {
				result.put(key, after.get(key));
			}
		}
		return result;
	}

	private boolean equal(Object one, Object two) {
		if (one == null && two == null) {
			return true;
		}
		if (one == null || two == null) {
			return false;
		}
		return one.equals(two);
	}

	private Map<String, Object> extract(MutablePropertySources propertySources) {
		Map<String, Object> result = new HashMap<String, Object>();
		List<PropertySource<?>> sources = new ArrayList<PropertySource<?>>();
		for (PropertySource<?> source : propertySources) {
			sources.add(0, source);
		}
		for (PropertySource<?> source : sources) {
			if (!this.standardSources.contains(source.getName())) {
				extract(source, result);
			}
		}
		return result;
	}

	private void extract(PropertySource<?> parent, Map<String, Object> result) {
		if (parent instanceof CompositePropertySource) {
			try {
				List<PropertySource<?>> sources = new ArrayList<PropertySource<?>>();
				for (PropertySource<?> source : ((CompositePropertySource) parent)
						.getPropertySources()) {
					sources.add(0, source);
				}
				for (PropertySource<?> source : sources) {
					extract(source, result);
				}
			}
			catch (Exception e) {
				return;
			}
		}
		else if (parent instanceof EnumerablePropertySource) {
			for (String key : ((EnumerablePropertySource<?>) parent).getPropertyNames()) {
				result.put(key, parent.getProperty(key));
			}
		}
	}

	@Configuration
	protected static class Empty {

	}

}
