package com.vivareal.gradle

import java.util.concurrent.TimeUnit

import org.gradle.api.*
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest
import com.amazonaws.services.ec2.model.DescribeInstanceStatusResult
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.InstanceStatus
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
import com.amazonaws.services.elasticbeanstalk.model.CreateApplicationVersionRequest
import com.amazonaws.services.elasticbeanstalk.model.CreateEnvironmentRequest
import com.amazonaws.services.elasticbeanstalk.model.DeleteApplicationVersionRequest
import com.amazonaws.services.elasticbeanstalk.model.DescribeApplicationVersionsRequest
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentResourcesRequest
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentResourcesResult
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsRequest
import com.amazonaws.services.elasticbeanstalk.model.S3Location
import com.amazonaws.services.elasticbeanstalk.model.SwapEnvironmentCNAMEsRequest
import com.amazonaws.services.elasticbeanstalk.model.UpdateEnvironmentRequest
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.CrossZoneLoadBalancing
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerAttributes
import com.amazonaws.services.elasticloadbalancing.model.ModifyLoadBalancerAttributesRequest
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3Client

class ElasticBeanstalkPlugin implements Plugin<Project> {

    def applicationName
    def previousEnvironmentName
    def versionLabel
    def configTemplate
    def warFilePath
    def newEnvironmentName
    def hotDeploy
    def tier
    def prodUrlPrefix

    AWSCredentials awsCredentials

    final def APP_ENV_NAMESPACE = "aws:elasticbeanstalk:application:environment"
    final def JVM_OPTS_NAMESPACE = "aws:elasticbeanstalk:container:tomcat:jvmoptions"
    final def LAUNCH_CONFIG_OPTS_NAMESPACE = "aws:autoscaling:launchconfiguration"
    final def BEANSTALK_ENV_PROPERTY_PREFIX = "beanstalk.env."
    final def BEANSTALK_JVM_PARAMETER_PREFIX = "beanstalk.jvm."
    final def BEANSTALK_LAUNCH_CONFIG_PARAMETER_PREFIX = "beanstalk.launchconfig."
    def namespaceParameters  = [:]


    void apply(Project project) {

	namespaceParameters[BEANSTALK_ENV_PROPERTY_PREFIX] = APP_ENV_NAMESPACE
	namespaceParameters[BEANSTALK_JVM_PARAMETER_PREFIX] = JVM_OPTS_NAMESPACE
	namespaceParameters[BEANSTALK_LAUNCH_CONFIG_PARAMETER_PREFIX] = LAUNCH_CONFIG_OPTS_NAMESPACE

	awsCredentials = getCredentials(project)

	AWSElasticBeanstalk elasticBeanstalk
	AmazonElasticLoadBalancing loadBalancer
	AmazonAutoScalingClient autoScaling
	AmazonEC2Client ec2
	AmazonS3 s3

	previousEnvironmentName = project.ext.has('currentEnvironment')?project.ext.currentEnvironment:null
	applicationName = project.ext.has('applicationName')?project.ext.applicationName:null
	configTemplate = project.ext.has('configTemplate')?project.ext.configTemplate:null
	warFilePath = project.ext.has('warFilePath')?project.ext.warFilePath:null
	//if no new evn name is provided, use version as env name
	newEnvironmentName = project.ext.has('newEnvironmentName')?project.ext.newEnvironmentName:versionLabel
	versionLabel = project.ext.has('versionLabel')?project.ext.versionLabel:versionLabel
	hotDeploy = project.ext.has('hotDeploy')?project.ext.hotDeploy.toBoolean():true
    tier = project.ext.has('tier') ? project.ext.tier:"web"
    prodUrlPrefix = project.ext.has('prodUrlPrefix')?project.ext.prodUrlPrefix:null


	if (awsCredentials) {
	    s3 = new AmazonS3Client(awsCredentials)
	    elasticBeanstalk = new AWSElasticBeanstalkClient(awsCredentials)
	    loadBalancer = new AmazonElasticLoadBalancingClient(awsCredentials)
	    autoScaling = new AmazonAutoScalingClient(awsCredentials)
	    ec2 = new AmazonEC2Client(awsCredentials)

	    try {
		def region = Region.getRegion(Regions.SA_EAST_1)
		if (project.ext.has('awsRegion')) {
		    region = Region.getRegion(Regions.valueOf(project.ext.awsRegion))
		}
		elasticBeanstalk.setRegion(region)
		loadBalancer.setRegion(region)
		autoScaling.setRegion(region)
		ec2.setRegion(region)
	    } catch (Exception e) {
	    	// should fail here
		throw new org.gradle.api.tasks.StopExecutionException(e)
	    }

	}

	def getDeploymentOptionsByPrefix = { prefix ->
	    def selectedProperties = project.ext.properties.findAll {
		it.key.startsWith(prefix)
	    }
	    def environmentOptions = []
	    selectedProperties.each { key, value ->
		def realKey = key.substring(prefix.length())
		def config = new ConfigurationOptionSetting()
		config.setNamespace(namespaceParameters[prefix])
		config.setOptionName(realKey)
		config.setValue(value)
		environmentOptions << config
	    }
	    environmentOptions
	}

	def getOptionsSettings = {
	    def envOptions = getDeploymentOptionsByPrefix(BEANSTALK_ENV_PROPERTY_PREFIX)
	    envOptions.addAll(getDeploymentOptionsByPrefix(BEANSTALK_JVM_PARAMETER_PREFIX))
	    envOptions.addAll(getDeploymentOptionsByPrefix(BEANSTALK_LAUNCH_CONFIG_PARAMETER_PREFIX))
	    return envOptions
	}

	project.task('testBeanstalk')<<{

	    println "Application Name: ${applicationName}"
	    println "New Environment Name: ${environmentName}"
	    println "Current Environment:  ${previousEnvironmentName}"
	    println "AWS credentials: $elasticBeanstalk"
	    println "AWS Region:  ${awsCredentials}"
	    println "Config Template: ${configTemplate}"
	    println "Root project version $project.rootProject.version"
	    println "Project's version $project.version"
	    println "Version label $versionLabel"

	}

	//dependsOn: 'uploadNewVersion',
	project.task([
	    description: "deploys a new version to a new Elastic Beanstalk environment with zero downtime"],'deployBeanstalkZeroDowntime') << {

	    //println project.ext.appName
	    //if (!project.ext.appName)
	    //	throw new org.gradle.api.tasks.StopExecutionException()

	    //Copy existing production configuration or use API-PROD

	    //Create new Environment
	    println "Create new environment for new application version"
	    def createEnvironmentRequest = new CreateEnvironmentRequest(applicationName: applicationName, environmentName:  environmentName, versionLabel: versionLabel, templateName: configTemplate)
	    createEnvironmentRequest.setCNAMEPrefix(createEnvironmentRequest.getEnvironmentName())
	    createEnvironmentRequest.setOptionSettings(getOptionsSettings())
	    def createEnvironmentResult = elasticBeanstalk.createEnvironment(createEnvironmentRequest)
	    println "Created environment $createEnvironmentResult"

	    if (!createEnvironmentResult.environmentId)
		throw new org.gradle.api.tasks.StopExecutionException()

	    enableCrossZoneLoadBalancing(loadBalancer,elasticBeanstalk,environmentName)
	    println "Added Cross-zone load balancing to environment ${environmentName}"

	    //If it gets here, environment is ready. Check health next

	    println("Checking that new environment's health is Green before swapping urls")

	    def search = new DescribeEnvironmentsRequest(environmentNames: [environmentName])
	    def result = elasticBeanstalk.describeEnvironments(search)

	    println(result.environments.health.toString())
	    if (result.environments.health.toString() != "[Green]")
		throw new org.gradle.api.tasks.StopExecutionException("Environment is not Green, cannot continue")

	    println("Environment's health is Green")

	    // Swap new environment with previous environment
	    // NOTE: Envirnments take too long to be green. Swapping right after deployment is not a good idea.
	    println "Swap environment Url"
	    def swapEnviromentRequest = new SwapEnvironmentCNAMEsRequest(destinationEnvironmentName: previousEnvironmentName , sourceEnvironmentName: environmentName)

	    try{
		elasticBeanstalk.swapEnvironmentCNAMEs(swapEnviromentRequest)
		println "Swaped CNAMES Successfully"

	    }catch(Exception e){
		println("En error ocurred while swapping environment CNAMEs " + e)
	    }

	}

	project.task([dependsOn: 'uploadNewVersion',
	    description: "deploys a new version to an existing Elastic Beanstalk environment"],'deployBeanstalk') << {

	    //override env name

	    def finalEnvName = previousEnvironmentName?previousEnvironmentName:environmentName;

	    println "Checking if environment ${finalEnvName} exists"

	    def search = new DescribeEnvironmentsRequest(environmentNames: [finalEnvName], applicationName:applicationName, includeDeleted: false)
	    def result = elasticBeanstalk.describeEnvironments(search)

	    if (!result.environments.empty) {
            if (!hotDeploy) {
                throw new RuntimeException("The environment is already running, choose another environment or specify -PhotDeploy=true")
            }

            if (prodUrlPrefix) {
              def isProductionEnv = result.environments['CNAME'].any { it =~ prodUrlPrefix }
              if (isProductionEnv) {
                throw new IllegalStateException("Attempting to update the production environment: $finalEnvName")
              }
            }

            //Deploy the new version to the new environment
            println "Update environment with uploaded application version"
            def updateEnviromentRequest = new UpdateEnvironmentRequest(environmentName:  finalEnvName, versionLabel: versionLabel)
            updateEnviromentRequest.setOptionSettings(getOptionsSettings())
            def updateEnviromentResult = elasticBeanstalk.updateEnvironment(updateEnviromentRequest)
            println "Updated environment $updateEnviromentResult"
	    } else {
            println("Environment doesn't exist. creating environment")
            def createEnvironmentRequest = new CreateEnvironmentRequest(applicationName: applicationName, environmentName:  finalEnvName, versionLabel: versionLabel, templateName: configTemplate)
            if (tier == "web") {
                createEnvironmentRequest.setCNAMEPrefix(createEnvironmentRequest.getEnvironmentName())
            }
            createEnvironmentRequest.setOptionSettings(getOptionsSettings())
            def createEnvironmentResult = elasticBeanstalk.createEnvironment(createEnvironmentRequest)
            println "Created environment $createEnvironmentResult"
            enableCrossZoneLoadBalancing(loadBalancer,elasticBeanstalk,finalEnvName)
            println "Added Cross-zone load balancing to environment ${finalEnvName}"
	    }

	}

	project.task([description: "deploys an existing version to an existing Elastic Beanstalk environment"],'deployBeanstalkVersion') << {
        def tier = project.ext.has('tier') ? project.ext.tier:"web"

	    try {
            //Deploy the existing version to the new environment
            println "Update environment with existing application version ${versionLabel}"
            def updateEnviromentRequest = new UpdateEnvironmentRequest(environmentName:  previousEnvironmentName, versionLabel: versionLabel)
            updateEnviromentRequest.setOptionSettings(getOptionsSettings())
            def updateEnviromentResult = elasticBeanstalk.updateEnvironment(updateEnviromentRequest)
            println "Updated environment $updateEnviromentResult"
        } catch(Exception ipe) {
            println("Environment doesn't exist. creating environment")
            def createEnvironmentRequest = new CreateEnvironmentRequest(applicationName: applicationName, environmentName:  previousEnvironmentName, versionLabel: versionLabel, templateName: configTemplate)
            if (tier.equals("web")) {
                createEnvironmentRequest.setCNAMEPrefix(createEnvironmentRequest.getEnvironmentName())
            }
            createEnvironmentRequest.setOptionSettings(getOptionsSettings())
            def createEnvironmentResult = elasticBeanstalk.createEnvironment(createEnvironmentRequest)
            println "Created environment $createEnvironmentResult"
            enableCrossZoneLoadBalancing(loadBalancer,elasticBeanstalk,previousEnvironmentName)
            println "Added Cross-zone load balancing to environment ${previousEnvironmentName}"
	    }

	}

	project.task([description: "deploys an existing version to an existing Elastic Beanstalk environment with no downtime"],'deployBeanstalkVersionZeroDowntime') << {

	    try{
		//Deploy the existing version to the new environment
		println "Update environment with existing application version ${versionLabel}"
		// increase desired capacity
		DescribeEnvironmentResourcesRequest request = new DescribeEnvironmentResourcesRequest(environmentName: previousEnvironmentName)
		DescribeEnvironmentResourcesResult result = elasticBeanstalk.describeEnvironmentResources(request)
		def autoScalingGroups = result.environmentResources.autoScalingGroups
		def autoScalingGroupName = autoScalingGroups[0].name

		DescribeAutoScalingGroupsRequest describeAsRequest = new  DescribeAutoScalingGroupsRequest(autoScalingGroupNames: [autoScalingGroupName])
		DescribeAutoScalingGroupsResult describeAsResponse = autoScaling.describeAutoScalingGroups(describeAsRequest)
		AutoScalingGroup asGroup = describeAsResponse.autoScalingGroups[0]

		def newDesiredCapacity = (asGroup.desiredCapacity + 1 > asGroup.maxSize) ? asGroup.maxSize : asGroup.desiredCapacity + 1

		UpdateAutoScalingGroupRequest updateAsRequest =
			new UpdateAutoScalingGroupRequest(autoScalingGroupName: autoScalingGroupName, desiredCapacity: newDesiredCapacity)
		autoScaling.updateAutoScalingGroup(updateAsRequest)

		println "Desired capacity of auto scaling group increased"

		allInstancesHealthy(ec2,autoScaling,autoScalingGroupName,newDesiredCapacity)

		def updateEnviromentRequest = new UpdateEnvironmentRequest(environmentName:  previousEnvironmentName, versionLabel: versionLabel)
		def updateEnviromentResult = elasticBeanstalk.updateEnvironment(updateEnviromentRequest)
		println "Updated environment $updateEnviromentResult"
	    }catch(Exception ipe){
		println ipe
		println("Environment doesn't exist")
	    }

	}

	// Add a task that swaps environment CNAMEs
	project.task('uploadNewVersion') << {

	    //Check existing version and check if environment is production
	    //depends(checkExistingAppVersion, prodEnviroment, war)
	    //depends(war)
	    // Log on to AWS with your credentials

	    //this needs to go here because otherwise the root project version is used.
	    this.versionLabel = project.ext.has('versionLabel')?project.ext.versionLabel:project.version.toString()

	    if (!project.ext.has('skipUpload')){
		// Delete existing application
		if (applicationVersionAlreadyExists(elasticBeanstalk)) {
		    println "Delete existing application version"
		    def deleteRequest = new DeleteApplicationVersionRequest(applicationName:     applicationName,
		    versionLabel: versionLabel, deleteSourceBundle: true)
		    elasticBeanstalk.deleteApplicationVersion(deleteRequest)
		}

		// Upload a WAR file to Amazon S3
		println "Uploading application to Amazon S3"
		def warFile = projectWarFilename(project)
		String bucketName = elasticBeanstalk.createStorageLocation().getS3Bucket()
		String key = URLEncoder.encode("${project.name}-${versionLabel}.war", 'UTF-8')
		def s3Result = s3.putObject(bucketName, key, warFile)
		println "Uploaded application $s3Result.versionId"

		// Register a new application version
		println "Create application version with uploaded application"
		def createApplicationRequest = new CreateApplicationVersionRequest(
			applicationName: applicationName, versionLabel: versionLabel,
			description: description,
			autoCreateApplication: true, sourceBundle: new S3Location(bucketName, key)
			)
		def createApplicationVersionResult = elasticBeanstalk.createApplicationVersion(createApplicationRequest)
		println "Registered application version $createApplicationVersionResult"

	    }
	}

		project.task('swapEnvironmentUrl') << {

			if (!project.ext.has('targetUrl')) {
				throw new RuntimeException('You should provide an target url with the "targetUrl" property (for example production-bazinga.elasticbeanstalk.com)')
			}
			String targetUrl = project.get('targetUrl')

			if (!project.ext.has('newEnvironment')) {
				throw new RuntimeException('You should provide the name of the environment you want to put at ' + targetUrl)
			}
			String newEnvironment = project.get('newEnvironment')

			new SwapEnvironmentUrlsTask(elasticBeanstalk, this.applicationName.toString(), targetUrl, newEnvironment).execute()

		}


    }

    private boolean applicationVersionIsDeployed(elasticBeanstalk) {
	def search = new DescribeEnvironmentsRequest(applicationName: applicationName, versionLabel: versionLabel)
	def result = elasticBeanstalk.describeEnvironments(search)
	!result.environments.empty
    }

    private boolean applicationVersionAlreadyExists(elasticBeanstalk) {
	def search = new DescribeApplicationVersionsRequest(applicationName: applicationName, versionLabels: [versionLabel])
	def result = elasticBeanstalk.describeApplicationVersions(search)
	!result.applicationVersions.empty
    }

    private AWSCredentials getCredentials(Project project) {
	def accessKey = project.ext.has('accessKey')?project.ext.accessKey:null
	def secretKey = project.ext.has('secretKey')?project.ext.secretKey:null

	if (accessKey && secretKey)
	    awsCredentials = new BasicAWSCredentials(accessKey, secretKey)

	awsCredentials
    }

    private String getEnvironmentName() {
	newEnvironmentName
    }

    private String getDescription() {
	applicationName + " via 'gradle' build on ${new Date().format('yyyy-MM-dd')}"
    }

    private File projectWarFilename(Project project) {
	if (!warFilePath)// If no war file path was specified, look in /build/libs
	    new File(project.buildDir,"libs/${project.name}-${versionLabel}.war")
	else
	    new File("${warFilePath}")
    }

    @groovy.transform.TimedInterrupt(value = 20L, unit = TimeUnit.MINUTES, applyToAllClasses=false)
    private environmentIsReady(elasticBeanstalk, environmentName) {
	def done = false
	try {
	    while( !done ) {
		println("Checking if new environment is ready/green")

		def search = new DescribeEnvironmentsRequest(environmentNames: environmentName)
		def result = elasticBeanstalk.describeEnvironments(search)

		println(result.environments.status.toString())
		if (result.environments.status.toString() == "[Ready]"){
		    done = true
		}
		sleep(20000)
	    }
	}
	catch( e ) {
	    throw e
	}
    }

    @groovy.transform.TimedInterrupt(value = 5L, unit = TimeUnit.MINUTES, applyToAllClasses=false)
    private allInstancesHealthy(AmazonEC2Client ec2, AmazonAutoScalingClient autoScaling, autoScalingGroupName, desiredNumberofInstances) {
	def sleepAfterScaling = 60000
	def done = false
	try {
	    while(!done) {
		done = true
		println("Checking if all instances in autoscaling group [" + autoScalingGroupName + "] are ready")

		DescribeAutoScalingGroupsRequest asRequest = new DescribeAutoScalingGroupsRequest(autoScalingGroupNames: [autoScalingGroupName])
		DescribeAutoScalingGroupsResult asResult = autoScaling.describeAutoScalingGroups(asRequest)

		List<Instance> instances = asResult.autoScalingGroups.get(0).instances
		def instanceIds = instances*.instanceId

		DescribeInstanceStatusRequest statusRequest = new DescribeInstanceStatusRequest().withInstanceIds(instanceIds)
		DescribeInstanceStatusResult statusResult = ec2.describeInstanceStatus(statusRequest)
		List<InstanceStatus> instanceStatuses = statusResult.instanceStatuses

		if(instanceStatuses.size() >= desiredNumberofInstances) {
		    instanceStatuses.eachWithIndex() { obj, i ->
			println ">> instance " + instanceIds[i] + " status is " + obj.instanceStatus.details[0].status
			if(obj.instanceStatus.details[0].status != "passed") {
			    done = false
			}
		    }
		} else {
		    println ">> not enough instances running"
		    done = false
		}
		sleep(20000)
	    }
	    println ">> waiting for " + sleepAfterScaling + " ms after scaling activity and new instance passing status checks"
	    sleep(sleepAfterScaling)
	}
	catch( e ) {
	    throw e
	}
    }

    private enableCrossZoneLoadBalancing(AmazonElasticLoadBalancing loadBalancer,AWSElasticBeanstalk elasticBeanstalk,environmentName) {
	// assume the beanstalk environment has only one load balancer since it is default behavior
	try {
	    environmentIsReady(elasticBeanstalk,[environmentName])

	} catch(Exception e){
	    org.codehaus.groovy.runtime.StackTraceUtils.sanitize(e).printStackTrace()
	    throw new org.gradle.api.tasks.StopExecutionException()
	}

	def request = new DescribeEnvironmentResourcesRequest(environmentName: environmentName)
	def response = elasticBeanstalk.describeEnvironmentResources(request)
	def loadBalancers = response.environmentResources.loadBalancers.name
	def loadBalancerName = loadBalancers[0]

	def attributes = new LoadBalancerAttributes()
	attributes.crossZoneLoadBalancing = new CrossZoneLoadBalancing(enabled: true)

	request = new ModifyLoadBalancerAttributesRequest(loadBalancerName: loadBalancerName, loadBalancerAttributes: attributes)
	response = loadBalancer.modifyLoadBalancerAttributes(request)

	response
    }
}
