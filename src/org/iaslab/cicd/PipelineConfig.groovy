package org.iaslab.cicd

class PipelineConfig implements Serializable {
    String serviceName
    String nexusHost
    String deployTarget
}
