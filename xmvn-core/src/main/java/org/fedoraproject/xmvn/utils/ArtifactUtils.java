/*-
 * Copyright (c) 2012-2014 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fedoraproject.xmvn.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlSerializer;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.fedoraproject.xmvn.artifact.Artifact;
import org.fedoraproject.xmvn.artifact.DefaultArtifact;

/**
 * @author Mikolaj Izdebski
 */
public class ArtifactUtils
{
    private static final Logger logger = LoggerFactory.getLogger( ArtifactUtils.class );

    public static final String DEFAULT_EXTENSION = "jar";

    public static final String DEFAULT_VERSION = "SYSTEM";

    public static final String UNKNOWN_VERSION = "UNKNOWN";

    public static final String UNKNOWN_NAMESPACE = "UNKNOWN";

    public static final String MF_KEY_GROUPID = "JavaPackages-GroupId";

    public static final String MF_KEY_ARTIFACTID = "JavaPackages-ArtifactId";

    public static final String MF_KEY_EXTENSION = "JavaPackages-Extension";

    public static final String MF_KEY_CLASSIFIER = "JavaPackages-Classifier";

    public static final String MF_KEY_VERSION = "JavaPackages-Version";

    /**
     * Dummy artifact. Any dependencies on this artifact will be removed during model validation.
     */
    public static final Artifact DUMMY = new DefaultArtifact( "org.fedoraproject.xmvn:xmvn-void:SYSTEM" );

    /**
     * The same as {@code DUMMY}, but in JPP style. Any dependencies on this artifact will be removed during model
     * validation.
     */
    public static final Artifact DUMMY_JPP = new DefaultArtifact( "JPP/maven:empty-dep:SYSTEM" );

    /**
     * Convert a collection of artifacts to a human-readable string. This function uses single-line representation.
     * 
     * @param collection collection of artifacts
     * @return string representation of given collection of artifacts
     */
    public static String collectionToString( Collection<Artifact> set )
    {
        return collectionToString( set, false );
    }

    /**
     * Convert a collection of artifacts to a human-readable string.
     * 
     * @param collection collection of artifacts
     * @param multiLine if multi-line representation should be used instead of single-line
     * @return string representation of given collection of artifacts
     */
    public static String collectionToString( Collection<Artifact> collection, boolean multiLine )
    {
        if ( collection.isEmpty() )
            return "[]";

        String separator = multiLine ? System.lineSeparator() : " ";
        String indent = multiLine ? "  " : "";

        StringBuilder sb = new StringBuilder();
        sb.append( "[" + separator );

        Iterator<Artifact> iter = collection.iterator();
        sb.append( indent + iter.next() );

        while ( iter.hasNext() )
        {
            sb.append( "," + separator );
            sb.append( indent + iter.next() );
        }

        sb.append( separator + "]" );
        return sb.toString();
    }

    private static void addOptionalChild( Xpp3Dom parent, String tag, String value, String defaultValue )
    {
        if ( defaultValue == null || !value.equals( defaultValue ) )
        {
            Xpp3Dom child = new Xpp3Dom( tag );
            child.setValue( value );
            parent.addChild( child );
        }
    }

    public static Xpp3Dom toXpp3Dom( Artifact artifact, String tag )
    {
        Xpp3Dom parent = new Xpp3Dom( tag );

        addOptionalChild( parent, "namespace", artifact.getScope(), "" );
        addOptionalChild( parent, "groupId", artifact.getGroupId(), null );
        addOptionalChild( parent, "artifactId", artifact.getArtifactId(), null );
        addOptionalChild( parent, "extension", artifact.getExtension(), "jar" );
        addOptionalChild( parent, "classifier", artifact.getClassifier(), "" );
        addOptionalChild( parent, "version", artifact.getVersion(), "SYSTEM" );

        return parent;
    }

    public static void serialize( Artifact artifact, XmlSerializer serializer, String namespace, String tag )
        throws IOException
    {
        Xpp3Dom dom = toXpp3Dom( artifact, tag );
        dom.writeToSerializer( namespace, serializer );
    }

    private static final Map<String, ArtifactType> stereotypes = new LinkedHashMap<>();

    private static void addStereotype( String type, String extension, String classifier )
    {
        stereotypes.put( type, new DefaultArtifactType( type, extension, classifier, "java" ) );
    }

    // The list was taken from MavenRepositorySystemUtils in maven-aether-provider.
    static
    {
        addStereotype( "maven-plugin", "jar", "" );
        addStereotype( "ejb", "jar", "" );
        addStereotype( "ejb-client", "jar", "client" );
        addStereotype( "test-jar", "jar", "tests" );
        addStereotype( "javadoc", "jar", "javadoc" );
        addStereotype( "java-source", "jar", "sources" );
    }

    public static Artifact createTypedArtifact( String groupId, String artifactId, String type, String classifier,
                                                String version )
    {
        String extension = type != null ? type : ArtifactUtils.DEFAULT_EXTENSION;

        ArtifactType artifactType = stereotypes.get( type );
        if ( artifactType != null )
        {
            extension = artifactType.getExtension();
            if ( StringUtils.isEmpty( classifier ) )
                classifier = artifactType.getClassifier();
        }

        return new DefaultArtifact( groupId, artifactId, extension, classifier, version );
    }

    private static String getManifestValue( Manifest manifest, String key, String defaultValue )
    {
        Attributes attributes = manifest.getMainAttributes();
        String value = attributes.getValue( key );
        return value != null ? value : defaultValue;
    }

    private static Artifact getArtifactFromManifest( Path path )
        throws IOException
    {
        try (JarFile jarFile = new JarFile( path.toFile() ))
        {
            Manifest mf = jarFile.getManifest();
            if ( mf == null )
                return null;

            String groupId = getManifestValue( mf, ArtifactUtils.MF_KEY_GROUPID, null );
            String artifactId = getManifestValue( mf, ArtifactUtils.MF_KEY_ARTIFACTID, null );
            String extension = getManifestValue( mf, ArtifactUtils.MF_KEY_EXTENSION, ArtifactUtils.DEFAULT_EXTENSION );
            String classifier = getManifestValue( mf, ArtifactUtils.MF_KEY_CLASSIFIER, "" );
            String version = getManifestValue( mf, ArtifactUtils.MF_KEY_VERSION, ArtifactUtils.DEFAULT_VERSION );

            if ( groupId == null || artifactId == null )
                return null;

            return new DefaultArtifact( groupId, artifactId, extension, classifier, version );
        }
    }

    private static Artifact getArtifactFromPomProperties( Path path, String extension )
        throws IOException
    {
        try (ZipInputStream zis = new ZipInputStream( new FileInputStream( path.toFile() ) ))
        {
            ZipEntry entry;
            while ( ( entry = zis.getNextEntry() ) != null )
            {
                String name = entry.getName();
                if ( name.startsWith( "META-INF/maven/" ) && name.endsWith( "/pom.properties" ) )
                {
                    Properties properties = new Properties();
                    properties.load( zis );

                    String groupId = properties.getProperty( "groupId" );
                    String artifactId = properties.getProperty( "artifactId" );
                    String version = properties.getProperty( "version" );
                    return new DefaultArtifact( groupId, artifactId, extension, version );
                }
            }

            return null;
        }
    }

    public static Artifact readArtifactDefinition( Path path, String extension )
    {
        try
        {
            Artifact artifact = getArtifactFromManifest( path );
            if ( artifact != null )
                return artifact;

            artifact = getArtifactFromPomProperties( path, extension );
            if ( artifact != null )
                return artifact;

            return null;
        }
        catch ( IOException e )
        {
            logger.error( "Failed to get artifact definition from file {}", path, e );
            return null;
        }
    }
}
