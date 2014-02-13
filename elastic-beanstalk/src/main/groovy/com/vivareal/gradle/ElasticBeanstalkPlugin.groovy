package com.vivareal.gradle

import org.gradle.api.*
import org.gradle.api.plugins.*
import com.amazonaws.auth.*
import com.amazonaws.services.s3.*
import com.amazonaws.services.elasticbeanstalk.*
import com.amazonaws.services.elasticbeanstalk.model.*
import java.util.concurrent.TimeUnit
import com.amazonaws.AmazonServiceException
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions

class ElasticBeanstalkPlugin implements Plugin<Project> {

    def applicationName
    def previousEnvironmentName
    def versionLabel
    def configTemplate
    AWSCredentials awsCredentials
    def warFilePath
    def newEnvironmentName

    void apply(Project project) {

	previousEnvironmentName = project.ext.has('currentEnvironment')?project.ext.currentEnvironment:null
	applicationName = project.ext.has('applicationName')?project.ext.applicationName:null
	configTemplate = project.ext.has('configTemplate')?project.ext.configTemplate:null
	warFilePath = project.ext.has('warFilePath')?project.ext.warFilePath:null
	//if no new evn name is provided, use version as env name
	newEnvironmentName = project.ext.has('newEnvironmentName')?project.ext.newEnvironmentName:versionLabel

	awsCredentials = getCredentials(project)
	AWSElasticBeanstalk elasticBeanstalk;

	AmazonS3 s3;
	if (awsCredentials){
	    s3 = new AmazonS3Client(awsCredentials)
	    elasticBeanstalk = new AWSElasticBeanstalkClient(awsCredentials)
	    try{
		if (project.ext.has('awsRegion')){
		    elasticBeanstalk.setRegion(Region.getRegion(Regions.valueOf(project.ext.awsRegion)))
		}else {
		    elasticBeanstalk.setRegion(Region.getRegion(Regions.SA_EAST_1))
		}
	    }catch(Exception e){
		elasticBeanstalk.setRegion(Region.getRegion(Regions.SA_EAST_1))
	    }

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
	    def createEnvironmentResult = elasticBeanstalk.createEnvironment(createEnvironmentRequest)
	    println "Created environment $createEnvironmentResult"

	    if (!createEnvironmentResult.environmentId)
		throw new org.gradle.api.tasks.StopExecutionException()


	    //Check if new Environment is ready
	    try{
		environmentIsReady(elasticBeanstalk,[environmentName])

	    }catch(Exception e){
		org.codehaus.groovy.runtime.StackTraceUtils.sanitize(e).printStackTrace()
		throw new org.gradle.api.tasks.StopExecutionException()
	    }

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

	    def search = new DescribeEnvironmentsRequest(environmentNames: [finalEnvName], applicationName:applicationName)
	    def result = elasticBeanstalk.describeEnvironments(search)

	    if (!result.environments.empty){
		//Deploy the new version to the new environment
		println "Update environment with uploaded application version"
		def updateEnviromentRequest = new UpdateEnvironmentRequest(environmentName:  finalEnvName, versionLabel: versionLabel)
		def updateEnviromentResult = elasticBeanstalk.updateEnvironment(updateEnviromentRequest)
		println "Updated environment $updateEnviromentResult"
	    }else{
		println("Environment doesn't exist. creating environment")
		def createEnvironmentRequest = new CreateEnvironmentRequest(applicationName: applicationName, environmentName:  finalEnvName, versionLabel: versionLabel, templateName: configTemplate)
		def createEnvironmentResult = elasticBeanstalk.createEnvironment(createEnvironmentRequest)
		println "Created environment $createEnvironmentResult"
	    }

	}

	project.task([description: "deploys an existing version to an existing Elastic Beanstalk environment"],'deployBeanstalkVersion') << {

	    try{

		//Deploy the existing version to the new environment
		println "Update environment with existing application version ${versionLabel}"
		def updateEnviromentRequest = new UpdateEnvironmentRequest(environmentName:  previousEnvironmentName, versionLabel: versionLabel)
		def updateEnviromentResult = elasticBeanstalk.updateEnvironment(updateEnviromentRequest)
		println "Updated environment $updateEnviromentResult"

	    }catch(Exception ipe){
		println("Environment doesn't exist. creating environment")
		def createEnvironmentRequest = new CreateEnvironmentRequest(applicationName: applicationName, environmentName:  previousEnvironmentName, versionLabel: versionLabel, templateName: configTemplate)
		def createEnvironmentResult = elasticBeanstalk.createEnvironment(createEnvironmentRequest)
		println "Created environment $createEnvironmentResult"
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

    @groovy.transform.TimedInterrupt(value = 20L, unit = TimeUnit.MINUTES)
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
}

