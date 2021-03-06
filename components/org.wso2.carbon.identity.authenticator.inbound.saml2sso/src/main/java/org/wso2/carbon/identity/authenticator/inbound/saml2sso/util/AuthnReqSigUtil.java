/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.authenticator.inbound.saml2sso.util;

import org.apache.commons.lang.StringUtils;
import org.opensaml.saml2.core.AuthnRequest;
import org.opensaml.saml2.core.StatusCode;
import org.opensaml.ws.transport.http.HTTPTransportUtils;
import org.opensaml.xml.security.CriteriaSet;
import org.opensaml.xml.security.SecurityException;
import org.opensaml.xml.security.SecurityHelper;
import org.opensaml.xml.security.credential.CollectionCredentialResolver;
import org.opensaml.xml.security.credential.Credential;
import org.opensaml.xml.security.credential.UsageType;
import org.opensaml.xml.security.criteria.EntityIDCriteria;
import org.opensaml.xml.security.criteria.UsageCriteria;
import org.opensaml.xml.security.keyinfo.KeyInfoCredentialResolver;
import org.opensaml.xml.security.x509.X509Credential;
import org.opensaml.xml.signature.SignatureTrustEngine;
import org.opensaml.xml.signature.SignatureValidator;
import org.opensaml.xml.signature.impl.ExplicitKeySignatureTrustEngine;
import org.opensaml.xml.util.Base64;
import org.opensaml.xml.util.DatatypeHelper;
import org.opensaml.xml.validation.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.auth.saml2.common.X509CredentialImpl;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.bean.SAML2SSOContext;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.exception.SAML2SSORequestValidationException;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.exception.SAML2SSOServerException;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.model.RequestValidatorConfig;
import org.wso2.carbon.identity.authenticator.inbound.saml2sso.request.SPInitRequest;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilities pertaining to validating signatures in AuthnRequest.
 */
public class AuthnReqSigUtil {

    private static Logger logger = LoggerFactory.getLogger(AuthnReqSigUtil.class);

    public static boolean validateAuthnRequestSignature(AuthnRequest authnRequest, SAML2SSOContext saml2SSOContext,
                                                        RequestValidatorConfig config)
            throws SAML2SSORequestValidationException, SAML2SSOServerException {

        String encodedCert = config.getSigningCertificate();
        X509Certificate certificate;
        try {
            certificate = (X509Certificate) Utils.decodeCertificate(encodedCert);
        } catch (CertificateException e) {
            SAML2SSOServerException ex =
                    new SAML2SSOServerException(StatusCode.RESPONDER_URI,
                                                "Error occurred while decoding signing certificate.", e);
            ex.setInResponseTo(authnRequest.getID());
            ex.setAcsUrl(authnRequest.getDestination());
            throw ex;
        }

        SPInitRequest spInitRequest = ((SPInitRequest) saml2SSOContext.getRequest());
        if (spInitRequest.isRedirect()) {
            return validateDeflateSignature(spInitRequest.getQueryString(), spInitRequest.getSignature(),
                                            spInitRequest.getSignatureAlgorithm(), certificate, saml2SSOContext.getId(),
                                            saml2SSOContext.getAssertionConsumerURL(), saml2SSOContext.getSPEntityId());
        } else {
            return validateXMLSignature(authnRequest, certificate, saml2SSOContext.getId(),
                                        saml2SSOContext.getAssertionConsumerURL());
        }
    }

    public static boolean validateDeflateSignature(String queryString, String signature,
                                                   String sigAlg, X509Certificate certificate,
                                                   String inResponseTo, String acsUrl, String spEntityId)
            throws SAML2SSORequestValidationException {

        if (StringUtils.isBlank(signature)) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI,
                                                           "Could not extract the Signature from query string.");
            ex.setInResponseTo(inResponseTo);
            ex.setAcsUrl(acsUrl);
            throw ex;
        }
        byte[] sigBytes = Base64.decode(signature);
        byte[] signedContent = getSignedContent(queryString, inResponseTo, acsUrl);

        if (StringUtils.isBlank(sigAlg)) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI,
                                                           "Could not extract signature algorithm from query string.");
            ex.setInResponseTo(inResponseTo);
            ex.setAcsUrl(acsUrl);
            throw ex;
        }

        CriteriaSet criteriaSet = buildCriteriaSet(spEntityId);

        X509Credential credential = new X509CredentialImpl(certificate);
        List<Credential> credentials = new ArrayList();
        credentials.add(credential);
        CollectionCredentialResolver credResolver = new CollectionCredentialResolver(credentials);
        KeyInfoCredentialResolver kiResolver = SecurityHelper.buildBasicInlineKeyInfoResolver();
        SignatureTrustEngine engine = new ExplicitKeySignatureTrustEngine(credResolver, kiResolver);
        try {
            return engine.validate(sigBytes, signedContent, sigAlg, criteriaSet, null);
        } catch (SecurityException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Signature validation error.", e);
            }
            return false;
        }
    }

    protected static byte[] getSignedContent(String queryString, String inResponseTo, String acsUrl)
            throws SAML2SSORequestValidationException {

        // We need the raw non-URL-decoded query string param values for HTTP-Redirect DEFLATE simple signature
        // validation. We have to construct a string containing the signature input by accessing the request directly.
        // We can't use the decoded parameters because we need the raw data and URL-encoding isn't canonical.
        String constructed = buildSignedContentString(queryString, inResponseTo, acsUrl);
        if (DatatypeHelper.isEmpty(constructed)) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI,
                                                           "Could not extract signed content string from query string");
            ex.setInResponseTo(inResponseTo);
            ex.setAcsUrl(acsUrl);
            throw ex;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Constructed signed content string for HTTP-Redirect DEFLATE " + constructed);
        }
        try {
            return constructed.getBytes(StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("JVM is required to support UTF-8 encoding.", e);
            }
            return new byte[0];
        }
    }

    /**
     * Extract the raw request parameters and build a string representation of
     * the content that was signed.
     */
    public static String buildSignedContentString(String queryString, String inResponseTo, String acsUrl)
            throws SAML2SSORequestValidationException {

        StringBuilder builder = new StringBuilder();

        // One of these two is mandatory
        if (!appendParameter(builder, queryString, "SAMLRequest") && !appendParameter(builder, queryString,
                                                                                      "SAMLResponse")) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI,
                                                           "Extract of SAMLRequest or SAMLResponse from query string " +
                                                           "failed.");
            ex.setInResponseTo(inResponseTo);
            ex.setAcsUrl(acsUrl);
            throw ex;
        }
        // This is optional
        appendParameter(builder, queryString, "RelayState");
        // This is mandatory, but has already been checked in superclass
        appendParameter(builder, queryString, "SigAlg");

        return builder.toString();
    }

    /**
     * Find the raw query string parameter indicated and append it to the string
     * builder.
     * <p/>
     * The appended value will be in the form 'paramName=paramValue' (minus the
     * quotes).
     *
     * @param builder
     *         string builder to which to append the parameter
     * @param queryString
     *         the URL query string containing parameters
     * @param paramName
     *         the name of the parameter to append
     * @return true if parameter was found, false otherwise
     */
    private static boolean appendParameter(StringBuilder builder, String queryString,
                                    String paramName) {
        String rawParam = HTTPTransportUtils.getRawQueryStringParameter(queryString, paramName);
        if (rawParam == null) {
            return false;
        }
        if (builder.length() > 0) {
            builder.append('&');
        }
        builder.append(rawParam);
        return true;
    }

    private static CriteriaSet buildCriteriaSet(String issuer) {
        CriteriaSet criteriaSet = new CriteriaSet();
        if (!DatatypeHelper.isEmpty(issuer)) {
            criteriaSet.add(new EntityIDCriteria(issuer));
        }
        criteriaSet.add(new UsageCriteria(UsageType.SIGNING));
        return criteriaSet;
    }

    public static boolean validateXMLSignature(AuthnRequest authnRequest, X509Certificate certificate,
                                               String inResponseTo, String acsUrl)
            throws SAML2SSORequestValidationException, SAML2SSOServerException {

        if (authnRequest.getSignature() == null) {
            SAML2SSORequestValidationException ex =
                    new SAML2SSORequestValidationException(StatusCode.REQUESTER_URI,
                                                           "Cannot find Signature element in AuthnRequest.");
            ex.setInResponseTo(inResponseTo);
            ex.setAcsUrl(acsUrl);
            throw ex;
        }

        X509Credential credential = new X509CredentialImpl(certificate);
        boolean isSignatureValid = false;
        if (authnRequest.getSignature() != null) {
            try {
                SignatureValidator validator = new SignatureValidator(credential);
                validator.validate(authnRequest.getSignature());
                isSignatureValid = true;
            } catch (ValidationException e) {
                return false;
            }
        }
        return isSignatureValid;
    }
}
