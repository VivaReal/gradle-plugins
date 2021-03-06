Gradle Plugins
==============

Gradle Plugins that can be used in different projects.

###Code Style

The code in this repository is currently formatted with VivaReal's default Eclipse Java code formatter.
You'll need it to see the code properly formatted in your IDE.
Please reframe from editing and commiting code without the formatter.

###Testing
This plugin uses gradle's maven plugin. To test locally just run `gradle install` for the corresponding plugin and the dependency will be installed in your local maven repo, which you can use right away on your project.

###Publishing to a remote repository
You can create a `gradle.properties` file at the root folder that declares the following properties:

```properties
repositoryReleasesUrl=http://your-remote-repository/releases
repositorySnapshotsUrl=http://your-remote-repository/snapshots
repositoryUser=user
repositoryPwd=password
```

With those properties properly set, you can execute `gradle uploadArchives` and the plugin(s) you execute the task for will be uploaded to your remote repository.

Note: you also can pass theses properties in your command-line:

```
gradle uploadArchives -PrepositoryReleasesUrl=[RELEASES_URL] -PrepositorySnapshotsUrl=[SNAPSHOT_URL] -PrepositoryUser=[USER] -PrepositoryPwd=[PASSWORD]
```


###Plugins
####elastic-beanstalk
Plugin to deploy you project to beanstalk.

If you use task `deployBeanstalk` or `deployBeanstalkZeroDowntime` you can pass tags to be created with your new environment.
You can send these tags in your build.gradle or as properties in your command-line.

**build.gradle**

```groovy
beanstalk {
    tags = [
            Tag1: 'value1',
            Tag2: 'value2'
    ]
}
```

**Command-line**

```
gradle (...) -Ptags=Tag1:value1,Tag2:value2
```

**Note:** If you use command-line property for tags, it will override the build.gradle `beanstalk.tags` object.


####aws-ec2
