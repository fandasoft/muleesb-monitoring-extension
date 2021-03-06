/**
 * Copyright 2014 AppDynamics, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.appdynamics.monitors.muleesb;

import com.appdynamics.extensions.PathResolver;
import com.appdynamics.monitors.muleesb.config.ConfigUtil;
import com.appdynamics.monitors.muleesb.config.Configuration;
import com.appdynamics.monitors.muleesb.config.MBeanData;
import com.appdynamics.monitors.muleesb.config.MuleESBMonitorConstants;
import com.appdynamics.monitors.muleesb.config.Server;
import com.google.common.base.Strings;
import com.singularity.ee.agent.systemagent.api.AManagedMonitor;
import com.singularity.ee.agent.systemagent.api.MetricWriter;
import com.singularity.ee.agent.systemagent.api.TaskExecutionContext;
import com.singularity.ee.agent.systemagent.api.TaskOutput;
import com.singularity.ee.agent.systemagent.api.exception.TaskExecutionException;
import org.apache.log4j.Logger;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MuleESBMonitor extends AManagedMonitor {
    private static final Logger logger = Logger.getLogger(MuleESBMonitor.class);

    public static final String METRICS_SEPARATOR = "|";
    private static final String CONFIG_ARG = "config-file";
    private static final String FILE_NAME = "monitors/MuleESBMonitor/config.yml";

    private static final ConfigUtil<Configuration> configUtil = new ConfigUtil<Configuration>();

    public MuleESBMonitor() {
        String details = MuleESBMonitor.class.getPackage().getImplementationTitle();
        String msg = "Using Monitor Version [" + details + "]";
        logger.info(msg);
        System.out.println(msg);
    }

    public TaskOutput execute(Map<String, String> taskArgs, TaskExecutionContext taskExecutionContext) throws TaskExecutionException {
        if (taskArgs != null) {
            logger.info("Starting the Mule ESB Monitoring task.");
            String configFilename = getConfigFilename(taskArgs.get(CONFIG_ARG));
            try {
                Configuration config = configUtil.readConfig(configFilename, Configuration.class);
                Map<String, Number> metrics = populateStats(config);
                metrics.put(MuleESBMonitorConstants.METRICS_COLLECTED, MuleESBMonitorConstants.SUCCESS_VALUE);
                printStats(config, metrics);
                logger.info("Completed the Mule ESB Monitoring Task successfully");
                return new TaskOutput("Mule ESB Monitor executed successfully");
            } catch (FileNotFoundException e) {
                logger.error("Config File not found: " + configFilename, e);
            } catch (Exception e) {
                logger.error("Metrics Collection Failed: ", e);
            }
        }
        throw new TaskExecutionException("Mule ESB Monitor completed with failures");
    }

    private Map<String, Number> populateStats(Configuration config) {

        Map<String, Number> metrics = new HashMap<String, Number>();

        Server server = config.getServer();
        JMXConnector jmxConnector = null;
        MBeanServerConnection mBeanServerConnection = null;
        try {
            try {
                jmxConnector = JMXUtil.getJmxConnector(server.getHost(), server.getPort(), server.getUsername(), server.getPassword());
                mBeanServerConnection = jmxConnector.getMBeanServerConnection();
            } catch (IOException e) {
                logger.error("Error JMX-ing into Mule ESB Server ", e);
                metrics.put(MuleESBMonitorConstants.METRICS_COLLECTED, MuleESBMonitorConstants.ERROR_VALUE);
                return metrics;
            }

            MBeanData mbeans = config.getMbeans();

            String domainMatcher = mbeans.getDomainMatcher();
            Set<String> types = mbeans.getTypes();
            Set<String> excludeDomains = mbeans.getExcludeDomains();

            for (String type : types) {
                String mbeanMatcher = buildMbeanMatcher(domainMatcher, type);

                try {
                    Set<ObjectInstance> objectInstances = JMXUtil.queryMbeans(mBeanServerConnection, mbeanMatcher);
                    Map<String, Number> curMetrics = extractMetrics(mBeanServerConnection, objectInstances, excludeDomains);
                    metrics.putAll(curMetrics);
                } catch (IOException e) {
                    logger.error("Error getting bean with type :" + type, e);
                } catch (MalformedObjectNameException e) {
                    logger.error("Error getting bean with type :" + type, e);
                }

            }
        } finally {
            if (jmxConnector != null) {
                try {
                    jmxConnector.close();
                } catch (IOException e) {
                    logger.error("Unable to close the connection", e);
                }
            }
        }
        return metrics;
    }

    private Map<String, Number> getMemoryMetrics(MBeanServerConnection mBeanServerConnection) {
        Map<String, Number> memoryMetrics = new HashMap<String, Number>();
        try {
            Set<ObjectInstance> mBeans = mBeanServerConnection.queryMBeans(new ObjectName("Mule.default:name=MuleContext,*"), null);
            ObjectInstance objectInstance = mBeans.iterator().next();
            ObjectName objectName = objectInstance.getObjectName();
            Object freeMemory = mBeanServerConnection.getAttribute(objectName, "FreeMemory");
            memoryMetrics.put("FreeMemory", (Number) freeMemory);
            Object maxMemory = mBeanServerConnection.getAttribute(objectName, "MaxMemory");
            memoryMetrics.put("MaxMemory", (Number) maxMemory);
            Object totalMemory = mBeanServerConnection.getAttribute(objectName, "TotalMemory");
            memoryMetrics.put("TotalMemory", (Number) totalMemory);
        } catch (Exception e) {
            logger.error("Unable to get memory stats", e);
        }
        return memoryMetrics;
    }

    private String buildMbeanMatcher(String domainMatcher, String type) {
        StringBuilder sb = new StringBuilder(domainMatcher);
        sb.append(":").append("type=").append(type).append(",*");
        return sb.toString();
    }

    private Map<String, Number> extractMetrics(MBeanServerConnection mBeanServerConnection, Set<ObjectInstance> objectInstances, Set<String> excludeDomains) {
        Map<String, Number> metrics = new HashMap<String, Number>();

        Map<String, Number> memoryMetrics = getMemoryMetrics(mBeanServerConnection);
        metrics.putAll(memoryMetrics);

        for (ObjectInstance objectInstance : objectInstances) {
            ObjectName objectName = objectInstance.getObjectName();
            String domain = objectName.getDomain();
            if (!isDomainExcluded(objectName, excludeDomains)) {
                try {
                    MBeanAttributeInfo[] attributes = mBeanServerConnection.getMBeanInfo(objectName).getAttributes();

                    for (MBeanAttributeInfo mBeanAttributeInfo : attributes) {
                        Object attribute = mBeanServerConnection.getAttribute(objectName, mBeanAttributeInfo.getName());
                        String metricKey = getMetricsKey(objectName, mBeanAttributeInfo);
                        if (attribute != null && attribute instanceof Number) {
                            metrics.put(metricKey, (Number) attribute);
                        } else {
                            logger.info("Excluded " + metricKey + " as its value can not be converted to number");
                        }
                    }

                } catch (Exception e) {
                    logger.error("Unable to get info for object " + objectInstance.getObjectName(), e);
                }
            } else {
                logger.info("Excluding domain: " + domain + " as configured");
            }
        }
        return metrics;
    }

    private boolean isDomainExcluded(ObjectName objectName, Set<String> excludeDomains) {
        String domain = objectName.getDomain();
        return excludeDomains.contains(domain);
    }

    private String getMetricsKey(ObjectName objectName, MBeanAttributeInfo attr) {
        StringBuilder metricsKey = new StringBuilder();
        metricsKey.append(objectName.getDomain()).append(METRICS_SEPARATOR).append(attr.getName());
        return metricsKey.toString();
    }

    private String getConfigFilename(String filename) {
        if (filename == null) {
            return "";
        }

        if ("".equals(filename)) {
            filename = FILE_NAME;
        }
        // for absolute paths
        if (new File(filename).exists()) {
            return filename;
        }
        // for relative paths
        File jarPath = PathResolver.resolveDirectory(AManagedMonitor.class);
        String configFileName = "";
        if (!Strings.isNullOrEmpty(filename)) {
            configFileName = jarPath + File.separator + filename;
        }
        return configFileName;
    }

    private void printStats(Configuration config, Map<String, Number> metrics) {
        String metricPath = config.getMetricPrefix();
        for (Map.Entry<String, Number> entry : metrics.entrySet()) {
            printMetric(metricPath + entry.getKey(), entry.getValue());
        }
    }

    private void printMetric(String metricPath, Number metricValue) {
        printMetric(metricPath, metricValue, MetricWriter.METRIC_AGGREGATION_TYPE_AVERAGE, MetricWriter.METRIC_TIME_ROLLUP_TYPE_AVERAGE,
                MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_COLLECTIVE);
    }

    private void printMetric(String metricPath, Number metricValue, String aggregation, String timeRollup, String cluster) {
        MetricWriter metricWriter = super.getMetricWriter(metricPath, aggregation, timeRollup, cluster);
        if (metricValue != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Metric [" + metricPath + " = " + metricValue + "]");
            }
            if (metricValue instanceof Double) {
                metricWriter.printMetric(String.valueOf(Math.round((Double) metricValue)));
            } else if (metricValue instanceof Float) {
                metricWriter.printMetric(String.valueOf(Math.round((Float) metricValue)));
            } else {
                metricWriter.printMetric(String.valueOf(metricValue));
            }
        }
    }

    public static void main(String[] args) throws TaskExecutionException {

        Map<String, String> taskArgs = new HashMap<String, String>();
        taskArgs.put(CONFIG_ARG, "/home/satish/AppDynamics/Code/extensions/muleesb-monitoring-extension/src/main/resources/config/config.yml");

        com.appdynamics.monitors.muleesb.MuleESBMonitor muleesbMonitor = new com.appdynamics.monitors.muleesb.MuleESBMonitor();
        muleesbMonitor.execute(taskArgs, null);
    }
}