/******************************************************************************* 
 * Copyright (c) 2008 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Xavier Coulon - Initial API and implementation 
 ******************************************************************************/

package org.jboss.tools.livereload.core.internal.server.jetty;

import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.jboss.tools.livereload.core.internal.util.HttpUtils;
import org.jboss.tools.livereload.core.internal.util.Logger;
import org.jboss.tools.livereload.core.internal.util.ScriptInjectionUtils;

/**
 * Filter that injects the link to the Livereload script at the bottom of the
 * returned HTML entities.
 * 
 * @author xcoulon
 * 
 */
public class LiveReloadScriptInjectionFilter implements Filter {

	/** The port on which to connect to use the LiveReload support. */
	private final String scriptContent;

	/**
	 * Constructor.
	 * 
	 * @param livereloadPort
	 * @throws UnknownHostException
	 */
	public LiveReloadScriptInjectionFilter(final int livereloadPort) {
		scriptContent = new StringBuilder(
				"<script>document.write('<script src=\"http://' + location.host.split(':')[0]+ ':")
						.append(livereloadPort).append("/livereload.js\"></'+ 'script>')</script>").toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,
	 * javax.servlet.ServletResponse, javax.servlet.FilterChain)
	 */
	@Override
	public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
			throws IOException, ServletException {
		final HttpServletRequest httpRequest = (HttpServletRequest) request;
		final HttpServletResponse httpResponse = (HttpServletResponse) response;
		final String acceptedContentTypes = httpRequest.getHeader("Accept");
		Logger.trace("Processing request {} {}", httpRequest.getMethod(), httpRequest.getRequestURI());
		if (!"/livereload".equals(httpRequest.getRequestURI()) && HttpUtils.isHtmlContentType(acceptedContentTypes)) {
			final ModifiableHttpServletResponse responseWrapper = new ModifiableHttpServletResponse(httpResponse);
			// follow the chain
			chain.doFilter(request, responseWrapper);
			// handle case when the next servlet/filter in the chain works
			// asynchronously
			terminate(httpRequest, responseWrapper);
		} else {
			final HttpServletResponseWrapper responseWrapper = new HttpServletResponseWrapper(httpResponse);
			chain.doFilter(request, response);
			terminate(httpRequest, responseWrapper);
		}
	}

	private void terminate(final HttpServletRequest httpRequest, final HttpServletResponseWrapper responseWrapper)
			throws IOException {
		if (httpRequest.isAsyncSupported() && httpRequest.isAsyncStarted()) {
			httpRequest.getAsyncContext().addListener(new AsyncListener() {

				@Override
				public void onTimeout(AsyncEvent event) throws IOException {
					Logger.error("Proxied request timed out");
					doTerminate(httpRequest, responseWrapper);
				}

				@Override
				public void onStartAsync(AsyncEvent event) throws IOException {
					Logger.debug("Proxied request async mode started");
				}

				@Override
				public void onError(AsyncEvent event) throws IOException {
					Logger.error("Proxied request failed", event.getThrowable());
					doTerminate(httpRequest, responseWrapper);
				}

				@Override
				public void onComplete(AsyncEvent event) throws IOException {
					Logger.debug("Proxied request completed");
					doTerminate(httpRequest, responseWrapper);
				}
			});
			// in case the request was completed while adding this listener
			if (!httpRequest.isAsyncStarted()) {
				doTerminate(httpRequest, responseWrapper);
			}
		} else {
			doTerminate(httpRequest, responseWrapper);
		}
	}

	/**
	 * @param response
	 * @param httpRequest
	 * @param responseWrapper
	 * @throws IOException
	 */
	private void doTerminate(final HttpServletRequest httpRequest, final HttpServletResponseWrapper responseWrapper)
			throws IOException {
		if(responseWrapper instanceof ModifiableHttpServletResponse) {
			doTerminate(httpRequest, (ModifiableHttpServletResponse)responseWrapper);
		} else {
			responseWrapper.flushBuffer();
		}
	}

	/**
	 * @param response
	 * @param httpRequest
	 * @param responseWrapper
	 * @throws IOException
	 */
	private void doTerminate(final HttpServletRequest httpRequest, final ModifiableHttpServletResponse responseWrapper)
			throws IOException {
		// post-process the response
		// retrieving the content-type header from the response
		// (HttpServletResponse#getContentType() returns null !)
		final String returnedContentType = responseWrapper.getHeader("Content-Type");
		Logger.trace(" response type: {}", returnedContentType);
		if (HttpUtils.isHtmlContentType(returnedContentType)) {
			Logger.debug("Injecting livereload.js <script> in response for {} ({})", httpRequest.getRequestURI(),
					returnedContentType);
			final InputStream responseStream = responseWrapper.getResponseAsStream();
			final char[] modifiedResponseContent = ScriptInjectionUtils.injectContent(responseStream, scriptContent);
			final Charset charset = HttpUtils.getContentCharSet(returnedContentType, "UTF-8");
			responseWrapper.terminate(modifiedResponseContent, charset);
		}
		// finalize the responseWrapper by copying the wrapper's
		// outputstream into the response outputstream that will be returned
		// to the client.
		else {
			Logger.trace("*Not* injecting livereload.js <script> in response for {} ({})", httpRequest.getRequestURI(),
					returnedContentType);
			responseWrapper.terminate();
		}

	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void destroy() {
	}

	/**
	 * Modifiable Http Servlet Response: extending the usual
	 * {@link HttpServletResponseWrapper} but using an internal and temporary
	 * {@link ByteArrayOutputStream} and allow for response content modification
	 * *after* the call to
	 * {@link FilterChain#doFilter(ServletRequest, ServletResponse)}.
	 * 
	 * @author xcoulon
	 * 
	 */
	public class ModifiableHttpServletResponse extends HttpServletResponseWrapper {

		private ByteArrayServletOutputStream responseOutputStream;

		public ModifiableHttpServletResponse(HttpServletResponse response) {
			super(response);
			this.responseOutputStream = new ByteArrayServletOutputStream();
		}

		/**
		 * Returns the internal {@link ServletOutputStream} instead of the
		 * wrapped {@link HttpServletResponse}'s one.
		 * 
		 * @see javax.servlet.ServletResponseWrapper#getOutputStream()
		 */
		@Override
		public ServletOutputStream getOutputStream() throws IOException {
			return responseOutputStream;
		}

		/**
		 * Returns the content of the underlying response outputstream as an
		 * readable inputstream. A the same time, the current outputstream is
		 * closed.
		 * 
		 * @return
		 * @throws IOException
		 */
		public InputStream getResponseAsStream() throws IOException {
			final byte[] byteArray = responseOutputStream.toByteArray();
			responseOutputStream.close();
			final String characterEncoding = getCharacterEncoding();
			return IOUtils.toInputStream(new String(byteArray, characterEncoding), characterEncoding);
		}

		/**
		 * Writes the given content into the wrapped {@link HttpServletResponse}
		 * and adjust the 'content-length' response header as well (in case
		 * content modification occurred in the response entity).
		 * 
		 * @param responseContent
		 *            the content of the response.
		 * @param encoding
		 *            the encoding to use when writing the char[] content into
		 *            the response's outputstream.
		 * 
		 * @throws IOException
		 */
		public void terminate(final char[] responseContent, final Charset charset) throws IOException {
			// see
			// http://mark.koli.ch/2009/09/remember-kids-an-http-content-length-is-the-number-of-bytes-not-the-number-of-characters.html
			final CharBuffer charBuffer = CharBuffer.wrap(responseContent);
			final ByteBuffer byteBuffer = charset.encode(charBuffer);
			((HttpServletResponse) getResponse()).setHeader("Content-length",
					Integer.toString(byteBuffer.array().length));
			getResponse().getOutputStream().write(byteBuffer.array());
			// responseOutputStream.close();
		}

		/**
		 * Writes the content of the internal and temporary
		 * {@link ByteArrayOutputStream} into the wrapped
		 * {@link HttpServletResponse}.
		 * 
		 * @throws IOException
		 */
		public void terminate() throws IOException {
			final byte[] responseContent = this.responseOutputStream.toByteArray();
			responseOutputStream.close();
			IOUtils.write(responseContent, getResponse().getOutputStream());
			getResponse().getOutputStream().flush();
			getResponse().getOutputStream().close();
		}

	}

	/**
	 * @author xcoulon
	 * 
	 */
	public class ByteArrayServletOutputStream extends ServletOutputStream {

		private ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

		@Override
		public void write(int b) throws IOException {
			write(new byte[] { (byte) b });
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			this.outputStream.write(b, off, len);
		}

		@Override
		public void write(byte[] b) throws IOException {
			this.outputStream.write(b);
		}

		public synchronized byte[] toByteArray() {
			return this.outputStream.toByteArray();
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener writeListener) {
		}

	}

}
