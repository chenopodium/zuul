/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package outbound

import com.netflix.zuul.filters.http.HttpOutboundFilter
import com.netflix.zuul.message.http.HttpRequestInfo
import com.netflix.zuul.message.http.HttpResponseMessage
import org.apache.commons.io.IOUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable

import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/**
 * TODO - move this into zuul-core?
 */
class GZipResponseFilter extends HttpOutboundFilter
{
    private static final Logger LOG = LoggerFactory.getLogger(GZipResponseFilter.class);

    @Override
    int filterOrder() {
        return 5
    }

    @Override
    boolean shouldFilter(HttpResponseMessage response) {
        return true
    }

    @Override
    Observable<HttpResponseMessage> applyAsync(HttpResponseMessage response)
    {
        HttpRequestInfo request = response.getInboundRequest()

        // Buffer the body ByteBufs into a single byte array, then potentially gzip or ungzip it,
        // and then set that as the new body on the HttpResponseMessage (which will in turn flag this
        // body as buffered from now on in the filter chain).
        return response.bufferBody()
                .map({bodyBytes ->

                    boolean isGzipRequested = HttpUtils.acceptsGzip(request.getHeaders())
                    boolean isResponseGzipped = HttpUtils.isGzipped(response.getHeaders())

                    // If origin response is gzipped, and client has not requested gzip, decompress stream
                    // before sending to client.
                    if (isResponseGzipped && !isGzipRequested) {
                        try {
                            byte[] unGzippedBody = IOUtils.toByteArray(new GZIPInputStream(new ByteArrayInputStream(bodyBytes)))
                            response.setBody(unGzippedBody)
                            response.getHeaders().remove("Content-Encoding")
                            response.getHeaders().set("Content-Length", Integer.toString(unGzippedBody.length))

                        } catch (ZipException e) {
                            LOG.error("Gzip expected but not received assuming unencoded response. So sending body as-is.")
                        }
                    }
                    // If origin response is not gzipped, but client accepts gzip, then compress the body.
                    else if (isGzipRequested && !isResponseGzipped) {
                        try {
                            byte[] gzippedBody = gzip(bodyBytes)
                            response.setBody(gzippedBody)
                            response.getHeaders().set("Content-Encoding", "gzip")
                            response.getHeaders().set("Content-Length", Integer.toString(gzippedBody.length))

                        } catch (ZipException e) {
                            LOG.error("Error gzipping response body. So just sending as-is.")
                        }
                    }

                    return response
                })
    }

    private byte[] gzip(byte[] data)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        GZIPOutputStream gzOut = new GZIPOutputStream(baos)
        gzOut.write(data)
        gzOut.close()

        return baos.toByteArray()
    }
}