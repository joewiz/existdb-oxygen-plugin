/*
 * eXist-db Oxygen Plugin
 * Copyright (C) 2026 The eXist-db Authors
 *
 * info@exist-db.org
 * https://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package com.existdb.oxygen.transform;

import com.existdb.oxygen.ExistContext;
import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.query.QueryRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/**
 * The {@link Transformer} handed to Oxygen for the "eXist-db (HTTP)" XQuery engine. Validation is
 * performed when the transformer is <em>created</em> (compile-check via the language service); this
 * object's job is execution: it runs the editor's query against eXist over HTTP and writes the
 * serialized result sequence to the transformation scenario's output target. (For a one-click run
 * without a scenario, use <b>eXist-db → Run Current Editor</b>.)
 */
public final class ExistXQueryTransformer extends Transformer {

  private final Map<String, Object> parameters = new HashMap<>();
  private final String query;
  private final String moduleLoadPath;
  private final String systemId;
  private Properties outputProperties = new Properties();
  private URIResolver uriResolver;
  private ErrorListener errorListener;

  public ExistXQueryTransformer(String query, String moduleLoadPath, String systemId) {
    this.query = query;
    this.moduleLoadPath = moduleLoadPath;
    this.systemId = systemId;
  }

  @Override
  public void transform(Source xmlSource, Result outputTarget) throws TransformerException {
    // Route to the editor's own server (the systemId), or the default for an unsaved query.
    ExistClient client = ExistContext.clientForSystemId(systemId);
    if (client == null) {
      throw new TransformerException("Connect to eXist-db first (eXist-db view → Connect…).");
    }
    if (query == null || query.isBlank()) {
      throw new TransformerException("No XQuery to execute.");
    }
    try {
      String contextItem = serializeSource(xmlSource);
      QueryRunner.QueryResult result =
          QueryRunner.execute(client, query, moduleLoadPath, contextItem);
      writeOutput(outputTarget, result.output());
    } catch (IOException e) {
      throw new TransformerException(e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransformerException("XQuery execution was interrupted.", e);
    }
  }

  /**
   * Serializes the transformation's input {@link Source} to a string to hand the server as the
   * {@code context-item}, so context-dependent expressions (e.g. {@code //para}) evaluate against
   * the document being queried (the XPath/XQuery Builder supplies the open document here). Returns
   * null when there is no usable input — an unsaved-query / no-context run — so no context item is
   * sent. Any serialization failure is swallowed (null), degrading to a context-free run rather than
   * failing the query.
   */
  private static String serializeSource(Source xmlSource) {
    if (xmlSource == null) {
      return null;
    }
    if (xmlSource instanceof StreamSource stream
        && stream.getReader() == null && stream.getInputStream() == null
        && (stream.getSystemId() == null || stream.getSystemId().isBlank())) {
      return null;
    }
    try {
      Transformer identity = TransformerFactory.newInstance().newTransformer();
      identity.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      StringWriter out = new StringWriter();
      identity.transform(xmlSource, new StreamResult(out));
      String serialized = out.toString();
      return serialized.isBlank() ? null : serialized;
    } catch (TransformerException | TransformerFactoryConfigurationError e) {
      return null;
    }
  }

  /** Writes the serialized output to a {@link StreamResult}'s writer or stream (UTF-8). */
  private static void writeOutput(Result target, String output) throws TransformerException {
    if (!(target instanceof StreamResult streamResult)) {
      throw new TransformerException(
          "Unsupported transformation output target: " + target.getClass().getName());
    }
    try {
      Writer writer = streamResult.getWriter();
      if (writer != null) {
        writer.write(output);
        writer.flush();
        return;
      }
      OutputStream out = streamResult.getOutputStream();
      if (out != null) {
        out.write(output.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return;
      }
      throw new TransformerException("Transformation output target has no writer or stream.");
    } catch (IOException e) {
      throw new TransformerException(e.getMessage(), e);
    }
  }

  @Override
  public void setParameter(String name, Object value) {
    parameters.put(name, value);
  }

  @Override
  public Object getParameter(String name) {
    return parameters.get(name);
  }

  @Override
  public void clearParameters() {
    parameters.clear();
  }

  @Override
  public void setURIResolver(URIResolver resolver) {
    this.uriResolver = resolver;
  }

  @Override
  public URIResolver getURIResolver() {
    return uriResolver;
  }

  @Override
  public void setOutputProperties(Properties oformat) {
    this.outputProperties = oformat != null ? oformat : new Properties();
  }

  @Override
  public Properties getOutputProperties() {
    return outputProperties;
  }

  @Override
  public void setOutputProperty(String name, String value) {
    outputProperties.setProperty(name, value);
  }

  @Override
  public String getOutputProperty(String name) {
    return outputProperties.getProperty(name);
  }

  @Override
  public void setErrorListener(ErrorListener listener) {
    this.errorListener = listener;
  }

  @Override
  public ErrorListener getErrorListener() {
    return errorListener;
  }
}
