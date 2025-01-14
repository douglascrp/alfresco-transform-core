/*
 * Copyright 2015-2022 Alfresco Software, Ltd.  All rights reserved.
 *
 * License rights for this program may be obtained from Alfresco Software, Ltd.
 * pursuant to a written agreement and any use of this program without such an
 * agreement is prohibited.
 */

package org.alfresco.transform.base.clients;

import static java.text.MessageFormat.format;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.alfresco.transform.base.MtlsTestUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * Used by Aspose t-engine and t-router, but likely to be useful in other t-engines.
 *
 * @author Cezar Leahu
 */
public class SfsClient
{
    static
    {
        ((Logger) LoggerFactory.getLogger("org.apache.http.client.protocol")).setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.apache.http.impl.conn")).setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.apache.http.headers")).setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.apache.http.wire")).setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.apache.http.wire")).setAdditive(false);
    }

    private static final String SFS_BASE_URL = MtlsTestUtils.isMtlsEnabled() ? "https://localhost:8099" : "http://localhost:8099";

    public static String uploadFile(final String fileToUploadName) throws Exception
    {
        return uploadFile(fileToUploadName, SFS_BASE_URL);
    }

    public static String uploadFile(final String fileToUploadName, final String sfsBaseUrl) throws Exception
    {
        final File file = readFile(fileToUploadName);

        final HttpPost post = new HttpPost(
            sfsBaseUrl+"/alfresco/api/-default-/private/sfs/versions/1/file");
        post.setEntity(MultipartEntityBuilder
            .create()
            .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
            .addPart("file", new FileBody(file, ContentType.DEFAULT_BINARY))
            .build());

        try (CloseableHttpClient client = MtlsTestUtils.getHttpClient())
        {
            final HttpResponse response = client.execute(post);
            int status = response.getStatusLine().getStatusCode();
            if (status >= 200 && status < 300)
            {
                return JacksonSerializer.readStringValue(EntityUtils.toString(response.getEntity()),
                    "entry.fileRef");
            }
            else
            {
                throw new Exception("Failed to upload source file to SFS");
            }
        }
    }

    private static File readFile(final String filename) throws Exception
    {
        final URL url = SfsClient.class.getClassLoader().getResource(filename);
        if (url == null)
        {
            throw new Exception("Failed to load resource URL with filename " + filename);
        }
        final URI uri = url.toURI();
        try
        {
            return Paths.get(uri).toFile();
        }
        catch (Exception e)
        {
            return readFileFromJar(uri);
        }
    }

    private static File readFileFromJar(final URI uri) throws Exception
    {
        final String[] array = uri.toString().split("!");
        try (final FileSystem fs = FileSystems.newFileSystem(URI.create(array[0]),
            ImmutableMap.of("create", "true")))
        {
            File temp = File.createTempFile("temp-", "", new File(System.getProperty("user.dir")));
            temp.deleteOnExit();
            Files.copy(fs.getPath(array[1]), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            temp.deleteOnExit();
            return temp;
        }
    }

    public static boolean checkFile(final String uuid) throws Exception 
    {
        return checkFile(uuid, SFS_BASE_URL);
    }

    public static boolean checkFile(final String uuid, final String sfsBaseUrl) throws Exception
    {
        final HttpHead head = new HttpHead(format(
            sfsBaseUrl+"/alfresco/api/-default-/private/sfs/versions/1/file/{0}",
            uuid));

        try (CloseableHttpClient client = MtlsTestUtils.getHttpClient())
        {
            final HttpResponse response = client.execute(head);
            final int status = response.getStatusLine().getStatusCode();
            return status >= 200 && status < 300;
        }
    }

    public static File downloadFile(final String uuid) throws Exception
    {
        return downloadFile(uuid, SFS_BASE_URL);
    }

    public static File downloadFile(final String uuid, final String sfsBaseUrl) throws Exception
    {
        final HttpGet get = new HttpGet(format(
            sfsBaseUrl+"/alfresco/api/-default-/private/sfs/versions/1/file/{0}",
            uuid));

        try (CloseableHttpClient client = MtlsTestUtils.getHttpClient())
        {
            final HttpResponse response = client.execute(get);
            final int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300)
            {
                throw new Exception("File with UUID " + uuid + " was not found on SFS");
            }
            final HttpEntity entity = response.getEntity();
            if (entity == null)
            {
                throw new Exception("Failed to read HTTP reply entity for file with UUID " + uuid);
            }

            final File file = File.createTempFile(uuid, "_tmp",
                new File(System.getProperty("user.dir")));
            file.deleteOnExit();

            try (OutputStream os = new FileOutputStream(file))
            {
                entity.writeTo(os);
            }
            return file;
        }
    }
}
