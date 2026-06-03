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

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;

/**
 * The {@link Transformer} handed to Oxygen for the "eXist-db (HTTP)" XQuery engine. Validation is
 * performed when the transformer is <em>created</em> (compile-check via the language service), so
 * this object's job is execution. Execution against eXist over HTTP is not wired up yet — for now
 * use the <b>eXist-db → Run XQuery…</b> view; running an editor's query through a transformation
 * scenario lands in a later iteration.
 */
public final class ExistXQueryTransformer extends Transformer {

  private final Map<String, Object> parameters = new HashMap<>();
  private Properties outputProperties = new Properties();
  private URIResolver uriResolver;
  private ErrorListener errorListener;

  @Override
  public void transform(Source xmlSource, Result outputTarget) throws TransformerException {
    throw new TransformerException(
        "Running XQuery through the eXist-db engine in a transformation scenario is not yet "
            + "supported. Use the eXist-db → Run XQuery… view to execute queries.");
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
