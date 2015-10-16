package com.vivareal.gradle

import java.util.concurrent.TimeUnit

import org.apache.commons.codec.binary.Base64
import org.gradle.api.*
import org.gradle.api.plugins.*

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*

class EC2Plugin implements Plugin<Project> {


	void apply(Project project) {

		project.task('launchEC2Instance') << {

			def userData = project.ext.has('project.userData')?project.userData:"";
			def region = project.ext.has('project.region')?project.region:"sa-east-1";
			def desiredNumInstance = project.ext.has('project.desiredNumInstance') ? Integer.parseInt(project.desiredNumInstance) : 1;

			def credentials
			if (project.accessKey && project.secretKey)
				credentials = new BasicAWSCredentials(project.accessKey, project.secretKey)

			AmazonEC2 ec2 = new AmazonEC2Client(credentials);
			ec2.setEndpoint("https://ec2." + region + ".amazonaws.com");

			for(i in 1..desiredNumInstance) {
				def subnetId
				if(i % 2 == 0) {
					subnetId = project.primarySubnetId
				} else {
					subnetId = project.secondarySubnetId
				}

				RunInstancesRequest runInstancesRequest = new RunInstancesRequest()
						.withInstanceType("${project.instanceType}")
						.withImageId("${project.imageId}")
						.withMinCount(1)
						.withMaxCount(1)
						.withSubnetId("${subnetId}")
						.withSecurityGroupIds("${project.securityGroupId}")
						.withKeyName("${project.keyName}")
						.withUserData(Base64.encodeBase64String("${userData}".getBytes()))
						.withInstanceInitiatedShutdownBehavior("terminate")

				if(project.securityGroupId) {
					def groupIds = []
					groupIds.add(project.securityGroupId)
					runInstancesRequest.setSecurityGroupIds(groupIds)
				}

				RunInstancesResult runInstances = ec2.runInstances(runInstancesRequest)
				def instanceId = runInstances.reservation.instances.get(0).instanceId

				println("launchEC2Instance: instanceID = ${instanceId}")

				try{
					environmentIsReady(ec2,instanceId)
					CreateTagsRequest createTagsRequest = new CreateTagsRequest();
					createTagsRequest.withResources(instanceId) //
							.withTags(new Tag("Name", project.tag + "-" + i));
					ec2.createTags(createTagsRequest);

					println("launchEC2Instance: Instance ${instanceId} with tag ${project.tag} launched successfully")
				}catch(Exception e){
					org.codehaus.groovy.runtime.StackTraceUtils.sanitize(e).printStackTrace()
					throw new org.gradle.api.tasks.StopExecutionException()
				}
			}
		}
	}

	@groovy.transform.TimedInterrupt(value = 5L, unit = TimeUnit.MINUTES)
	private environmentIsReady(ec2, instanceId) {
		def done = false
		try {
			while( !done ) {
				println("launchEC2Instance: Checking if instance is in [passed] state")

				def request = new DescribeInstanceStatusRequest().withInstanceIds(instanceId)
				def result = ec2.describeInstanceStatus(request)

				// only query first instance (since we only pass one id only one instance should be returned)
				while (result.instanceStatuses.size() < 1) {
					result = ec2.describeInstanceStatus(request)
					sleep(5000)
				}

				def status = result.instanceStatuses[0].instanceStatus.details[0].status

				println("launchEC2Instance: Current status is [${status}]")

				// if instance is not running then sleep for 20 seconds and query again
				if (status == "passed")
					done = true
				else sleep(20000)
			}
		}
		catch( e ) {
			throw e
		}
	}

}

