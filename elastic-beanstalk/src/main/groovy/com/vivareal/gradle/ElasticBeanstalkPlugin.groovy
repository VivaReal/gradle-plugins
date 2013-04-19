package com.vivareal.gradle

import org.gradle.api.*
import org.gradle.api.plugins.*
import com.amazonaws.auth.*
import com.amazonaws.services.s3.*
import com.amazonaws.services.elasticbeanstalk.*
import com.amazonaws.services.elasticbeanstalk.model.*
import java.util.concurrent.TimeUnit


class ElasticBeanstalkPlugin implements Plugin<Project> {
	
	def applicationName = "VivaRealAPI"
	def previousEnvironmentName
	def versionLabel
	def configTemplate
	AWSCredentials credentials
	
    void apply(Project project) {
			
		previousEnvironmentName = project.ext.has('currentEnvironment')?project.ext.currentEnvironment:null
		applicationName = project.ext.has('applicationName')?project.ext.applicationName:null
		configTemplate = project.ext.has('configTemplate')?project.ext.configTemplate:null
	
		credentials = getCredentials(project)
		AWSElasticBeanstalk elasticBeanstalk;
		AmazonS3 s3;
		if (credentials){
			s3 = new AmazonS3Client(credentials)
			elasticBeanstalk = new AWSElasticBeanstalkClient(credentials)
		}
		
		project.task('testBeanstalk')<<{
			
			this.versionLabel = project.version
			
			println "Application Name: ${applicationName}"
			println "New Environment Name: ${environmentName}"
			println "Current Environment:  ${previousEnvironmentName}"
			println "AWS credentials:  ${credentials}"
			println "Config Template: ${configTemplate}" 
			println "Root project version $project.rootProject.version"
			println "Project's version $project.version"
			println "Version label $versionLabel"
			
		}
		
        project.task([dependsOn: 'uploadNewVersion',
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
		
		// Add a task that swaps environment CNAMEs
	    project.task([dependsOn: 'uploadNewVersion',
		                 description: "deploys a new version to an existing Elastic Beanstalk environment"],'deployBeanstalk') << {

			//Deploy the new version to the new environment
			println "Update environment with uploaded application version"
			def updateEnviromentRequest = new UpdateEnvironmentRequest(environmentName:  previousEnvironmentName, versionLabel: versionLabel)
			def updateEnviromentResult = elasticBeanstalk.updateEnvironment(updateEnviromentRequest)
			println "Updated environment $updateEnviromentResult"

		}
		
		
		// Add a task that swaps environment CNAMEs
	    project.task('uploadNewVersion') << {

		 //Check existing version and check if environment is production
		 //depends(checkExistingAppVersion, prodEnviroment, war)
		 //depends(war)
		 // Log on to AWS with your credentials
		
		this.versionLabel = project.version

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
	    	credentials = new BasicAWSCredentials(accessKey, secretKey)
	
	    credentials
	}
	
	private String getEnvironmentName() {
	    "VivaRealAPI-${versionLabel}"
	}
	
	private String getDescription() {
	    applicationName + " via 'gradle' build on ${new Date().format('yyyy-MM-dd')}"
	}

	private File projectWarFilename(Project project) {
		new File(project.buildDir,"libs/${project.name}.war")
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

