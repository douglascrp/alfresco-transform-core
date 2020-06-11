/*
 * #%L
 * Alfresco Transform Core
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * -
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 * -
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * -
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * -
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.transformer;

import org.alfresco.transform.client.model.TransformReply;
import org.alfresco.transform.client.model.TransformRequest;
import org.alfresco.transform.client.model.TransformRequestValidator;
import org.alfresco.transform.client.model.config.TransformConfig;
import org.alfresco.transform.client.registry.TransformServiceRegistry;
import org.alfresco.transform.exceptions.TransformException;
import org.alfresco.transformer.clients.AlfrescoSharedFileStoreClient;
import org.alfresco.transformer.logging.LogEntry;
import org.alfresco.transformer.model.FileRefResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.joining;
import static org.alfresco.transformer.fs.FileManager.TempFileProvider.createTempFile;
import static org.alfresco.transformer.fs.FileManager.buildFile;
import static org.alfresco.transformer.fs.FileManager.createAttachment;
import static org.alfresco.transformer.fs.FileManager.createSourceFile;
import static org.alfresco.transformer.fs.FileManager.createTargetFile;
import static org.alfresco.transformer.fs.FileManager.createTargetFileName;
import static org.alfresco.transformer.fs.FileManager.deleteFile;
import static org.alfresco.transformer.fs.FileManager.getFilenameFromContentDisposition;
import static org.alfresco.transformer.fs.FileManager.save;
import static org.alfresco.transformer.util.RequestParamMap.FILE;
import static org.alfresco.transformer.util.RequestParamMap.SOURCE_ENCODING;
import static org.alfresco.transformer.util.RequestParamMap.SOURCE_EXTENSION;
import static org.alfresco.transformer.util.RequestParamMap.SOURCE_MIMETYPE;
import static org.alfresco.transformer.util.RequestParamMap.TARGET_EXTENSION;
import static org.alfresco.transformer.util.RequestParamMap.TARGET_MIMETYPE;
import static org.alfresco.transformer.util.RequestParamMap.TEST_DELAY;
import static org.alfresco.transformer.util.RequestParamMap.TRANSFORM_NAME_PROPERTY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;
import static org.springframework.util.StringUtils.getFilenameExtension;

/**
 * <p>Abstract Controller, provides structure and helper methods to sub-class transformer controllers.</p>
 *
 * <p>Status Codes:</p>
 * <ul>
 * <li>200 Success</li>
 * <li>400 Bad Request: Request parameter <name> is missing (missing mandatory parameter)</li>
 * <li>400 Bad Request: Request parameter <name> is of the wrong type</li>
 * <li>400 Bad Request: Transformer exit code was not 0 (possible problem with the source file)</li>
 * <li>400 Bad Request: The source filename was not supplied</li>
 * <li>500 Internal Server Error: (no message with low level IO problems)</li>
 * <li>500 Internal Server Error: The target filename was not supplied (should not happen as targetExtension is checked)</li>
 * <li>500 Internal Server Error: Transformer version check exit code was not 0</li>
 * <li>500 Internal Server Error: Transformer version check failed to create any output</li>
 * <li>500 Internal Server Error: Could not read the target file</li>
 * <li>500 Internal Server Error: The target filename was malformed (should not happen because of other checks)</li>
 * <li>500 Internal Server Error: Transformer failed to create an output file (the exit code was 0, so there should be some content)</li>
 * <li>500 Internal Server Error: Filename encoding error</li>
 * <li>507 Insufficient Storage: Failed to store the source file</li>
 *
 * <li>408 Request Timeout         -- TODO implement general timeout mechanism rather than depend on transformer timeout
 * (might be possible for external processes)</li>
 * <li>415 Unsupported Media Type  -- TODO possibly implement a check on supported source and target mimetypes (probably not)</li>
 * <li>429 Too Many Requests: Returned by liveness probe</li>
 * </ul>
 * <p>Provides methods to help super classes perform /transform requests. Also responses to /version, /ready and /live
 * requests.</p>
 */
public abstract class AbstractTransformerController implements TransformController
{
    private static final Logger logger = LoggerFactory.getLogger(
        AbstractTransformerController.class);

    // Request parameters that are not part of transform options
    public static final List<String> NON_TRANSFORM_OPTION_REQUEST_PARAMETERS = Arrays.asList(SOURCE_EXTENSION,
            TARGET_EXTENSION, TARGET_MIMETYPE, SOURCE_MIMETYPE, TEST_DELAY, TRANSFORM_NAME_PROPERTY);

    @Autowired
    private AlfrescoSharedFileStoreClient alfrescoSharedFileStoreClient;

    @Autowired
    private TransformRequestValidator transformRequestValidator;

    @Autowired
    private TransformServiceRegistry transformRegistry;

    @GetMapping(value = "/transform/config")
    public ResponseEntity<TransformConfig> info()
    {
        logger.info("GET Transform Config.");
        final TransformConfig transformConfig =
            ((TransformRegistryImpl) transformRegistry).getTransformConfig();
        return new ResponseEntity<>(transformConfig, OK);
    }

    @PostMapping(value = "/transform", consumes = MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> transform(HttpServletRequest request,
                                              @RequestParam(FILE) MultipartFile sourceMultipartFile,
                                              @RequestParam(TARGET_EXTENSION) String targetExtension,
                                              @RequestParam(value = SOURCE_MIMETYPE, required = false) String sourceMimetype,
                                              @RequestParam(value = TARGET_MIMETYPE, required = false) String targetMimetype,
                                              @RequestParam Map<String, String> requestParameters,
                                              @RequestParam (value = TEST_DELAY, required = false) Long testDelay,

                                              // The TRANSFORM_NAME_PROPERTY param allows ACS legacy transformers to specify which transform to use,
                                              // It can be removed once legacy transformers are removed from ACS.
                                              @RequestParam (value = TRANSFORM_NAME_PROPERTY, required = false) String requestTransformName)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Processing request via HTTP endpoint. Params: sourceMimetype: '{}', targetMimetype: '{}', "
                    + "targetExtension: '{}', requestParameters: {}", sourceMimetype, targetMimetype, targetExtension, requestParameters);
        }

        final String targetFilename = createTargetFileName(
                sourceMultipartFile.getOriginalFilename(), targetExtension);
        getProbeTestTransform().incrementTransformerCount();
        final File sourceFile = createSourceFile(request, sourceMultipartFile);
        final File targetFile = createTargetFile(request, targetFilename);

        Map<String, String> transformOptions = getTransformOptions(requestParameters);
        String transformName = getTransformerName(sourceMimetype, targetMimetype, requestTransformName, sourceFile, transformOptions);
        transform(transformName, sourceMimetype, targetMimetype, transformOptions, sourceFile, targetFile);

        final ResponseEntity<Resource> body = createAttachment(targetFilename, targetFile);
        LogEntry.setTargetSize(targetFile.length());
        long time = LogEntry.setStatusCodeAndMessage(OK.value(), "Success");
        time += LogEntry.addDelay(testDelay);
        getProbeTestTransform().recordTransformTime(time);
        return body;
    }

    protected Map<String, String> getTransformOptions(Map<String, String> requestParameters)
    {
        Map<String, String> transformOptions = new HashMap<>(requestParameters);
        transformOptions.keySet().removeAll(NON_TRANSFORM_OPTION_REQUEST_PARAMETERS);
        transformOptions.values().removeIf(v -> v.isEmpty());
        return transformOptions;
    }

    /**
     * '/transform' endpoint which consumes and produces 'application/json'
     *
     * This is the way to tell Spring to redirect the request to this endpoint
     * instead of the one which produces 'html'
     *
     * @param request The transformation request
     * @param timeout Transformation timeout
     * @return A transformation reply
     */
    @PostMapping(value = "/transform", produces = APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<TransformReply> transform(@RequestBody TransformRequest request,
        @RequestParam(value = "timeout", required = false) Long timeout)
    {
        logger.info("Received {}, timeout {} ms", request, timeout);

        final TransformReply reply = new TransformReply();
        reply.setInternalContext(request.getInternalContext());
        reply.setRequestId(request.getRequestId());
        reply.setSourceReference(request.getSourceReference());
        reply.setSchema(request.getSchema());
        reply.setClientData(request.getClientData());

        final Errors errors = validateTransformRequest(request);
        if (!errors.getAllErrors().isEmpty())
        {
            reply.setStatus(BAD_REQUEST.value());
            reply.setErrorDetails(errors
                .getAllErrors()
                .stream()
                .map(Object::toString)
                .collect(joining(", ")));

            logger.error("Invalid request, sending {}", reply);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }

        // Load the source file
        File sourceFile;
        try
        {
            sourceFile = loadSourceFile(request.getSourceReference());
        }
        catch (TransformException e)
        {
            reply.setStatus(e.getStatusCode());
            reply.setErrorDetails(messageWithCause("Failed at reading the source file", e));

            logger.error("Failed to load source file (TransformException), sending " + reply);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }
        catch (HttpClientErrorException e)
        {
            reply.setStatus(e.getStatusCode().value());
            reply.setErrorDetails(messageWithCause("Failed at reading the source file", e));

            logger.error("Failed to load source file (HttpClientErrorException), sending " +
                         reply, e);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }
        catch (Exception e)
        {
            reply.setStatus(INTERNAL_SERVER_ERROR.value());
            reply.setErrorDetails(messageWithCause("Failed at reading the source file", e));

            logger.error("Failed to load source file (Exception), sending " + reply, e);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }

        // Create local temp target file in order to run the transformation
        final String targetFilename = createTargetFileName(sourceFile.getName(),
            request.getTargetExtension());
        final File targetFile = buildFile(targetFilename);

        // Run the transformation
        try
        {
            processTransform(sourceFile, targetFile, request.getSourceMediaType(),
                request.getTargetMediaType(), request.getTransformRequestOptions(), timeout);
        }
        catch (TransformException e)
        {
            reply.setStatus(e.getStatusCode());
            reply.setErrorDetails(messageWithCause("Failed at processing transformation", e));

            logger.error("Failed to perform transform (TransformException), sending " + reply, e);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }
        catch (Exception e)
        {
            reply.setStatus(INTERNAL_SERVER_ERROR.value());
            reply.setErrorDetails(messageWithCause("Failed at processing transformation", e));

            logger.error("Failed to perform transform (Exception), sending " + reply, e);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }

        // Write the target file
        FileRefResponse targetRef;
        try
        {
            targetRef = alfrescoSharedFileStoreClient.saveFile(targetFile);
        }
        catch (TransformException e)
        {
            reply.setStatus(e.getStatusCode());
            reply.setErrorDetails(messageWithCause("Failed at writing the transformed file", e));

            logger.error("Failed to save target file (TransformException), sending " + reply, e);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }
        catch (HttpClientErrorException e)
        {
            reply.setStatus(e.getStatusCode().value());
            reply.setErrorDetails(messageWithCause("Failed at writing the transformed file. ", e));

            logger.error("Failed to save target file (HttpClientErrorException), sending " + reply,
                e);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }
        catch (Exception e)
        {
            reply.setStatus(INTERNAL_SERVER_ERROR.value());
            reply.setErrorDetails(messageWithCause("Failed at writing the transformed file. ", e));

            logger.error("Failed to save target file (Exception), sending " + reply, e);
            return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
        }

        try
        {
            deleteFile(targetFile);
        }
        catch (Exception e)
        {
            logger.error("Failed to delete local temp target file '{}'. Error will be ignored ",
                targetFile, e);
        }
        try
        {
            deleteFile(sourceFile);
        }
        catch (Exception e)
        {
            logger.error("Failed to delete source local temp file " + sourceFile, e);
        }

        reply.setTargetReference(targetRef.getEntry().getFileRef());
        reply.setStatus(CREATED.value());

        logger.info("Sending successful {}, timeout {} ms", reply, timeout);
        return new ResponseEntity<>(reply, HttpStatus.valueOf(reply.getStatus()));
    }

    private Errors validateTransformRequest(final TransformRequest transformRequest)
    {
        DirectFieldBindingResult errors = new DirectFieldBindingResult(transformRequest, "request");
        transformRequestValidator.validate(transformRequest, errors);
        return errors;
    }

    /**
     * Loads the file with the specified sourceReference from Alfresco Shared File Store
     *
     * @param sourceReference reference to the file in Alfresco Shared File Store
     * @return the file containing the source content for the transformation
     */
    private File loadSourceFile(final String sourceReference)
    {
        ResponseEntity<Resource> responseEntity = alfrescoSharedFileStoreClient
            .retrieveFile(sourceReference);
        getProbeTestTransform().incrementTransformerCount();

        HttpHeaders headers = responseEntity.getHeaders();
        String filename = getFilenameFromContentDisposition(headers);

        String extension = getFilenameExtension(filename);
        MediaType contentType = headers.getContentType();
        long size = headers.getContentLength();

        final Resource body = responseEntity.getBody();
        if (body == null)
        {
            String message = "Source file with reference: " + sourceReference + " is null or empty. "
                             + "Transformation will fail and stop now as there is no content to be transformed.";
            logger.warn(message);
            throw new TransformException(BAD_REQUEST.value(), message);
        }
        final File file = createTempFile("source_", "." + extension);

        logger.debug("Read source content {} length={} contentType={}",
            sourceReference, size, contentType);

        save(body, file);
        LogEntry.setSource(filename, size);
        return file;
    }

    private static String messageWithCause(final String prefix, Throwable e)
    {
        final StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(" - ")
          .append(e.getClass().getSimpleName()).append(": ")
          .append(e.getMessage());

        while (e.getCause() != null)
        {
            e = e.getCause();
            sb.append(", cause ")
              .append(e.getClass().getSimpleName()).append(": ")
              .append(e.getMessage());
        }

        return sb.toString();
    }

    public void processTransform(final File sourceFile, final File targetFile,
                                  final String sourceMimetype, final String targetMimetype,
                                  final Map<String, String> transformOptions, final Long timeout)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Processing request with: sourceFile '{}', targetFile '{}', transformOptions" +
                            " '{}', timeout {} ms", sourceFile, targetFile, transformOptions, timeout);
        }

        String transformName = getTransformerName(sourceFile, sourceMimetype, targetMimetype, transformOptions);
        transform(transformName, sourceMimetype, targetMimetype, transformOptions, sourceFile, targetFile);
    }

    private String getTransformerName(String sourceMimetype, String targetMimetype,
                                      String requestTransformName, File sourceFile,
                                      Map<String, String> transformOptions)
    {
        // Check if transformName was provided in the request (this can happen for ACS legacy transformers)
        String transformName = requestTransformName;
        if (transformName == null || transformName.isEmpty())
        {
            transformName = getTransformerName(sourceFile, sourceMimetype, targetMimetype, transformOptions);
        }
        else if (logger.isInfoEnabled())
        {
            logger.info("Using transform name provided in the request: " + requestTransformName);
        }
        return transformName;
    }

    protected String getTransformerName(final File sourceFile, final String sourceMimetype,
        final String targetMimetype, final Map<String, String> transformOptions)
    {
        // The transformOptions always contains sourceEncoding when sent to a T-Engine, even though it should not be
        // used to select a transformer. Similar to source and target mimetypes and extensions, but these are not
        // passed in transformOptions.
        String sourceEncoding = transformOptions.remove(SOURCE_ENCODING);
        try
        {
            final long sourceSizeInBytes = sourceFile.length();
            final String transformerName = transformRegistry.findTransformerName(sourceMimetype,
                    sourceSizeInBytes, targetMimetype, transformOptions, null);
            if (transformerName == null)
            {
                throw new TransformException(BAD_REQUEST.value(),
                        "No transforms were able to handle the request");
            }
            return transformerName;
        }
        finally
        {
            if (sourceEncoding != null)
            {
                transformOptions.put(SOURCE_ENCODING, sourceEncoding);
            }
        }
    }

    protected Map<String, String> createTransformOptions(Object... namesAndValues)
    {
        if (namesAndValues.length % 2 != 0)
        {
            logger.error(
                "Incorrect number of parameters. Should have an even number as they are names and values.");
        }

        Map<String, String> transformOptions = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2)
        {
            String name = namesAndValues[i].toString();
            Object value = namesAndValues[i + 1];
            if (value != null && (!(value instanceof String) || !((String)value).isBlank()))
            {
                transformOptions.put(name, value.toString());
            }
        }
        return transformOptions;
    }

    protected abstract void transform(String transformName, String sourceMimetype, String targetMimetype,
                                      Map<String, String> transformOptions, File sourceFile, File targetFile);
}
