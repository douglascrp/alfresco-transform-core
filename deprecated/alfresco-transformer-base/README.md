# Common code for Transform Engines (Deprecated)

This project holds the original code that was common to all ACS T-Engine transformers. Although
it is still possible to create T-Engines this way, the newer `engine/base` project provides a
simpler way to do it.

> When upgrading to 3.0.0, you will find that a number of classes in the alfresco-transform-model
have moved. See the [alfresco-transform-model README](https://github.com/Alfresco/alfresco-transform-core/blob/master/model/README.md)

This project provides a base Spring Boot process (optionally within their own Docker containers).
It performs common actions such as logging, throttling requests and handling the streaming of content to and from the container.

For more details on build a custom T-Engine, please refer to the current docs in ACS Packaging, including:

* [ATS Configuration](https://github.com/Alfresco/acs-packaging/blob/master/docs/custom-transforms-and-renditions.md#ats-configuration)
* [Creating a T-Engine](https://github.com/Alfresco/acs-packaging/blob/master/docs/creating-a-t-engine.md)

## Overview

A transformer project is expected to provide the following files:

~~~
src/main/resources/templates/transformForm.html
src/main/java/org/alfresco/transformer/<TransformerName>Controller.java
src/main/java/org/alfresco/transformer/Application.java
~~~

* transformForm.html - A simple test page using [thymeleaf](http://www.thymeleaf.org) that gathers request
  parameters so they may be used to test the transformer.

~~~
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <div>
    <h2>Test Transformation</h2>
    <form method="POST" enctype="multipart/form-data" action="/transform">
      <table>
        <tr><td><div style="text-align:right">file *</div></td><td><input type="file" name="file" /></td></tr>
        <tr><td><div style="text-align:right">file *</div></td><td><input type="file" name="file" /></td></tr>
        <tr><td><div style="text-align:right">sourceExtension *</div></td><td><input type="text" name="sourceExtension" value="" /></td></tr>
        <tr><td><div style="text-align:right">targetExtension *</div></td><td><input type="text" name="targetExtension" value="" /></td></tr>
        <tr><td><div style="text-align:right">sourceMimetype *</div></td><td><input type="text" name="sourceMimetype" value="" /></td></tr>
        <tr><td><div style="text-align:right">targetMimetype *</div></td><td><input type="text" name="targetMimetype" value="" /></td></tr>
        <tr><td><div style="text-align:right">abc:width</div></td><td><input type="text" name="width" value="" /></td></tr>
        <tr><td><div style="text-align:right">abc:height</div></td><td><input type="text" name="height" value="" /></td></tr>
        <tr><td><div style="text-align:right">timeout</div></td><td><input type="text" name="timeout" value="" /></td></tr>
        <tr><td></td><td><input type="submit" value="Transform" /></td></tr>
	  </table>
	</form>
  </div>
  <div>
    <a href="/log">Log entries</a>
  </div>
</body>
</html>
~~~

* *TransformerName*Controller.java - A [Spring Boot](https://projects.spring.io/spring-boot/) Controller that
  extends TransformController to handle requests. It implements a few methods including *transformImpl*
  which is intended to perform the actual transform. Generally the transform is done in a sub class of
  *JavaExecutor*, when a Java library is being used or *AbstractCommandExecutor*, when an external process is used.
  Both are sub interfaces of *Transformer*.

~~~
...
@Controller
public class TransformerNameController extends TransformController
{
    private static final Logger logger = LoggerFactory.getLogger(TransformerNameController.class);

    TransformerNameExecutor executor;

    @PostConstruct
    private void init()
    {
        executor = new TransformerNameExecutor();
    }

    @Override
    public String getTransformerName()
    {
        return "Transformer Name";
    }

    @Override
    public String version()
    {
        return commandExecutor.version();
    }

    @Override
    public ProbeTestTransform getProbeTestTransform()
    {
        // See the Javadoc on this method and Probes.md for the choice of these values.
        return new ProbeTestTransform(this, "quick.pdf", "quick.png",
            7455, 1024, 150, 10240, 60 * 20 + 1, 60 * 15 - 15)
        {
            @Override
            protected void executeTransformCommand(File sourceFile, File targetFile)
            {
                transformImpl(null, null, null, Collections.emptyMap(), sourceFile, targetFile);
            }
        };
    }

    @Override
    public void transformImpl(String transformName, String sourceMimetype, String targetMimetype,
                                 Map<String, String> transformOptions, File sourceFile, File targetFile)
    {
        executor.transform(sourceMimetype, targetMimetype, transformOptions, sourceFile, targetFile);
    }
}
~~~

* *TransformerName*Executer.java - *JavaExecutor* and *CommandExecutor* sub classes need to extract values from
  *transformOptions* and use them in a call to an external process or as parameters to a library call.
~~~
...
public class TransformerNameExecutor extends AbstractCommandExecutor
{
    ...
    @Override
    public void transform(String transformName, String sourceMimetype, String targetMimetype,
                          Map<String, String> transformOptions,
                          File sourceFile, File targetFile) throws TransformException
    {
        final String options = TransformerNameOptionsBuilder
                .builder()
                .withWidth(transformOptions.get(WIDTH_REQUEST_PARAM))
                .withHeight(transformOptions.get(HEIGHT_REQUEST_PARAM))
                .build();

        Long timeout = stringToLong(transformOptions.get(TIMEOUT));

        run(options, sourceFile, targetFile, timeout);
    }
}
~~~

* Application.java - [Spring Boot](https://projects.spring.io/spring-boot/) expects to find an Application in
 a project's source files. The following may be used:

~~~
package org.alfresco.transformer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application
{
    public static void main(String[] args)
    {
        SpringApplication.run(Application.class, args);
    }
}
~~~

Transform requests are handled by the *TransformController*, but are either:
 * POST requests (a direct http request from a client) where the transform options are passed as parameters, the source is supplied as a multipart file and
   the response is a file download.
 * POST request (a request via a message queue) where the transform options are supplied as JSON and the response is also JSON.
   The source and target content is read from a location accessible to both the client and the transfomer.

**Example JSON request body**
```javascript
var transformRequest = {
	"requestId": "1",
	"sourceReference": "2f9ed237-c734-4366-8c8b-6001819169a4",
	"sourceMediaType": "application/pdf",
	"sourceSize": 123456,
	"sourceExtension": "pdf",
	"targetMediaType": "text/plain",
	"targetExtension": "txt",
	"clientType": "ACS",
	"clientData": "Yo No Soy Marinero, Soy Capitan, Soy Capitan!",
	"schema": 1,
	"transformRequestOptions": {
		"targetMimetype": "text/plain",
		"targetEncoding": "UTF-8",
		"abc:width": "120",
		"abc:height": "200"
	}
}
```

**Example JSON response body**

```javascript
var transformReply = {
    "requestId": "1",
    "status": 201,
    "errorDetails": null,
    "sourceReference": "2f9ed237-c734-4366-8c8b-6001819169a4",
    "targetReference": "34d69ff0-7eaa-4741-8a9f-e1915e6995bf",
    "clientType": "ACS",
    "clientData": "Yo No Soy Marinero, Soy Capitan, Soy Capitan!",
    "schema": 1
}
```

## Building and testing

The project can be built by running the Maven command:

~~~
mvn clean install
~~~

## Artifacts

The artifacts can be obtained by:

* downloading from the [Alfresco repository](https://artifacts.alfresco.com/nexus/content/groups/public/)
* Adding a Maven dependency to your pom file.

~~~
<dependency>
  <groupId>org.alfresco</groupId>
  <artifactId>alfresco-transformer-base</artifactId>
  <version>1.0</version>
</dependency>
~~~

and the Alfresco Maven repository:

~~~
<repository>
  <id>alfresco-maven-repo</id>
  <url>https://artifacts.alfresco.com/nexus/content/groups/public</url>
</repository>
~~~

The build plan is available in [GitHub Actions CI](https://github.com/Alfresco/alfresco-transform-core/actions).

## Contributing guide

Please use [this guide](https://github.com/Alfresco/alfresco-repository/blob/master/CONTRIBUTING.md)
to make a contribution to the project.

