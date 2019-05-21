/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.plugins.signing;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.Incubating;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.maven.MavenDeployment;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.internal.IConventionAware;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublicationArtifact;
import org.gradle.api.publish.internal.PublicationInternal;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.internal.Factory;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.plugins.signing.signatory.Signatory;
import org.gradle.plugins.signing.signatory.SignatoryProvider;
import org.gradle.plugins.signing.signatory.internal.gnupg.GnupgSignatoryProvider;
import org.gradle.plugins.signing.signatory.internal.pgp.InMemoryPgpSignatoryProvider;
import org.gradle.plugins.signing.signatory.pgp.PgpSignatoryProvider;
import org.gradle.plugins.signing.type.DefaultSignatureTypeProvider;
import org.gradle.plugins.signing.type.SignatureType;
import org.gradle.plugins.signing.type.SignatureTypeProvider;
import org.gradle.util.DeferredUtil;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.codehaus.groovy.runtime.StringGroovyMethods.capitalize;
import static org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation.castToBoolean;

/**
 * The global signing configuration for a project.
 */
public class SigningExtension {

    /**
     * The name of the configuration that all signature artifacts will be placed into ("signatures")
     */
    public static final String DEFAULT_CONFIGURATION_NAME = "signatures";

    /**
     * The project that the settings are for
     */
    final Project project;

    /**
     * The configuration that signature artifacts will be placed into.
     *
     * <p>Changing this will not affect any signing already configured.</p>
     */
    private Configuration configuration;

    private Object required = true;

    /**
     * The provider of signature types.
     */
    private SignatureTypeProvider signatureTypes;

    /**
     * The provider of signatories.
     */
    private SignatoryProvider signatories;

    /**
     * Configures the signing settings for the given project.
     */
    public SigningExtension(Project project) {
        this.project = project;
        this.configuration = getDefaultConfiguration();
        this.signatureTypes = createSignatureTypeProvider();
        this.signatories = createSignatoryProvider();
        project.getTasks().withType(Sign.class, new Action<Sign>() {
            @Override
            public void execute(Sign task) {
                addSignatureSpecConventions(task);
            }
        });
    }

    public final Project getProject() {
        return project;
    }

    /**
     * Whether or not this task should fail if no signatory or signature type are configured at generation time.
     * @since 4.0
     */
    public void setRequired(boolean required) {
        this.required = required;
    }

    /**
     * Whether or not this task should fail if no signatory or signature type are configured at generation time.
     *
     * If {@code required} is a {@link Callable}, it will be stored and "called" on demand (i.e. when {@link #isRequired()} is called) and the return value will be interpreting according to the Groovy
     * Truth. For example:
     *
     * <pre>
     * signing {
     *   required = { gradle.taskGraph.hasTask("uploadArchives") }
     * }
     * </pre>
     *
     * Because the task graph is not known until Gradle starts executing, we must use defer the decision. We can do this via using a {@link Closure} (which is a {@link Callable}).
     *
     * For any other type, the value will be stored and evaluated on demand according to the Groovy Truth.
     *
     * <pre>
     * signing {
     *   required = false
     * }
     * </pre>
     */
    public void setRequired(Object required) {
        this.required = required;
    }

    /**
     * Whether or not this task should fail if no signatory or signature type are configured at generation time.
     *
     * <p>Defaults to {@code true}.</p>
     *
     * @see #setRequired(Object)
     */
    public boolean isRequired() {
        return castToBoolean(force(required));
    }

    /**
     * Provides the configuration that signature artifacts are added to. Called once during construction.
     */
    protected Configuration getDefaultConfiguration() {
        ConfigurationContainer configurations = project.getConfigurations();
        Configuration configuration = configurations.findByName(DEFAULT_CONFIGURATION_NAME);
        return configuration != null
            ? configuration
            : configurations.create(DEFAULT_CONFIGURATION_NAME);
    }

    /**
     * Provides the signature type provider. Called once during construction.
     */
    protected SignatureTypeProvider createSignatureTypeProvider() {
        return new DefaultSignatureTypeProvider();
    }

    /**
     * Provides the signatory provider. Called once during construction.
     */
    protected SignatoryProvider createSignatoryProvider() {
        return new PgpSignatoryProvider();
    }

    /**
     * Configures the signatory provider (delegating to its {@link SignatoryProvider#configure(SigningExtension, Closure) configure method}).
     *
     * @param closure the signatory provider configuration DSL
     * @return the configured signatory provider
     */
    public SignatoryProvider signatories(Closure closure) {
        signatories.configure(this, closure);
        return signatories;
    }

    /**
     * The signatory that will be used for signing when an explicit signatory has not been specified.
     *
     * <p>Delegates to the signatory provider's default signatory.</p>
     */
    public Signatory getSignatory() {
        return signatories.getDefaultSignatory(project);
    }

    /**
     * The signature type that will be used for signing files when an explicit signature type has not been specified.
     *
     * <p>Delegates to the signature type provider's default type.</p>
     */
    public SignatureType getSignatureType() {
        return signatureTypes.getDefaultType();
    }

    public void setSignatureTypes(SignatureTypeProvider signatureTypes) {
        this.signatureTypes = signatureTypes;
    }

    public SignatureTypeProvider getSignatureTypes() {
        return signatureTypes;
    }

    public void setSignatories(SignatoryProvider signatories) {
        this.signatories = signatories;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    /**
     * Use GnuPG agent to perform signing work.
     * @since 4.5
     */
    @Incubating
    public void useGpgCmd() {
        setSignatories(new GnupgSignatoryProvider());
    }

    /**
     * Use the supplied ascii-armored in-memory PGP secret key and password
     * instead of reading it from a keyring.
     *
     * <pre><code>
     * signing {
     *     def secretKey = findProperty("mySigningKey")
     *     def password = findProperty("mySigningPassword")
     *     useInMemoryPgpKeys(secretKey, password)
     * }
     * </code></pre>
     *
     * @since 5.4
     */
    @Incubating
    public void useInMemoryPgpKeys(@Nullable String defaultSecretKey, @Nullable String defaultPassword) {
        setSignatories(new InMemoryPgpSignatoryProvider(defaultSecretKey, defaultPassword));
    }

    /**
     * The configuration that signature artifacts are added to.
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * Adds conventions to the given spec, using this settings object's default signatory and signature type as the default signatory and signature type for the spec.
     */
    protected void addSignatureSpecConventions(SignatureSpec spec) {
        if (!(spec instanceof IConventionAware)) {
            throw new InvalidUserDataException("Cannot add conventions to signature spec \'" + String.valueOf(spec) + "\' as it is not convention aware");
        }

        ConventionMapping conventionMapping = ((IConventionAware) spec).getConventionMapping();
        conventionMapping.map("signatory", new Callable<Signatory>() {
            @Override
            public Signatory call() {
                return getSignatory();
            }
        });
        conventionMapping.map("signatureType", new Callable<SignatureType>() {
            @Override
            public SignatureType call() {
                return getSignatureType();
            }
        });
        conventionMapping.map("required", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return isRequired();
            }
        });
    }

    /**
     * Creates signing tasks that depend on and sign the "archive" produced by the given tasks.
     *
     * <p>The created tasks will be named "sign<i>&lt;input task name capitalized&gt;</i>". That is, given a task with the name "jar" the created task will be named "signJar". <p> If the task is not
     * an {@link org.gradle.api.tasks.bundling.AbstractArchiveTask}, an {@link InvalidUserDataException} will be thrown.</p> <p> The signature artifact for the created task is added to the {@link
     * #getConfiguration() for this settings object}.
     *
     * @param tasks The tasks whose archives are to be signed
     * @return the created tasks.
     */
    public List<Sign> sign(Task... tasks) {
        List<Sign> result = new ArrayList<Sign>(tasks.length);
        for (final Task taskToSign : tasks) {
            result.add(
                createSignTaskFor(taskToSign.getName(), new Action<Sign>() {
                    @Override
                    public void execute(Sign task) {
                        task.setDescription("Signs the archive produced by the '" + taskToSign.getName() + "' task.");
                        task.sign(taskToSign);
                    }
                })
            );
        }
        return result;
    }

    /**
     * Creates signing tasks that sign {@link Configuration#getAllArtifacts() all artifacts} of the given configurations.
     *
     * <p>The created tasks will be named "sign<i>&lt;configuration name capitalized&gt;</i>". That is, given a configuration with the name "archives" the created task will be named "signArchives".
     *
     * The signature artifacts for the created tasks are added to the {@link #getConfiguration() configuration} for this settings object.
     *
     * @param configurations The configurations whose archives are to be signed
     * @return the created tasks.
     */
    public List<Sign> sign(Configuration... configurations) {
        List<Sign> result = new ArrayList<Sign>(configurations.length);
        for (final Configuration configurationToSign : configurations) {
            result.add(
                createSignTaskFor(configurationToSign.getName(), new Action<Sign>() {
                    @Override
                    public void execute(Sign task) {
                        task.setDescription("Signs all artifacts in the '" + configurationToSign.getName() + "' configuration.");
                        task.sign(configurationToSign);
                    }
                })
            );
        }
        return result;
    }

    /**
     * Creates signing tasks that sign all publishable artifacts of the given publications.
     *
     * <p>The created tasks will be named "sign<i>&lt;publication name capitalized&gt;</i>Publication".
     * That is, given a publication with the name "mavenJava" the created task will be named "signMavenJavaPublication".
     *
     * The signature artifacts for the created tasks are added to the publishable artifacts of the given publications.
     *
     * @param publications The publications whose artifacts are to be signed
     * @return the created tasks.
     * @since 4.8
     */
    @Incubating
    public List<Sign> sign(Publication... publications) {
        List<Sign> result = new ArrayList<Sign>(publications.length);
        for (final Publication publication : publications) {
            result.add(createSignTaskFor((PublicationInternal<?>) publication));
        }
        return result;
    }

    /**
     * Creates signing tasks that sign all publishable artifacts of the given publication collection.
     *
     * <p>The created tasks will be named "sign<i>&lt;publication name capitalized&gt;</i>Publication".
     * That is, given a publication with the name "mavenJava" the created task will be named "signMavenJavaPublication".
     *
     * The signature artifacts for the created tasks are added to the publishable artifacts of the given publications.
     *
     * @param publications The collection of publications whose artifacts are to be signed
     * @return the created tasks.
     * @since 4.8
     */
    @Incubating
    public List<Sign> sign(DomainObjectCollection<Publication> publications) {
        final List<Sign> result = new ArrayList<Sign>();
        publications.all(new Action<Publication>() {
            @Override
            public void execute(Publication publication) {
                result.add(createSignTaskFor((PublicationInternal<?>) publication));
            }
        });
        publications.whenObjectRemoved(new Action<Publication>() {
            @Override
            public void execute(Publication publication) {
                TaskContainer tasks = project.getTasks();
                Task task = tasks.getByName(determineSignTaskNameForPublication(publication));
                tasks.remove(task);
                result.remove(task);
            }
        });
        return result;
    }

    <T extends PublicationArtifact> Sign createSignTaskFor(final PublicationInternal<T> publicationToSign) {
        final Sign signTask = project.getTasks().create(determineSignTaskNameForPublication(publicationToSign), Sign.class, new Action<Sign>() {
            @Override
            public void execute(Sign task) {
                task.setDescription("Signs all artifacts in the '" + publicationToSign.getName() + "' publication.");
                task.sign(publicationToSign);
            }
        });
        final Map<Signature, T> artifacts = new HashMap<Signature, T>();
        signTask.getSignatures().all(new Action<Signature>() {
            @Override
            public void execute(final Signature signature) {
                T artifact = publicationToSign.addDerivedArtifact((T) signature.getSource(), new Factory<File>() {
                    @Override
                    public File create() {
                        return signature.getFile();
                    }
                });
                artifact.builtBy(signTask);
                artifacts.put(signature, artifact);
            }
        });
        signTask.getSignatures().whenObjectRemoved(new Action<Signature>() {
            @Override
            public void execute(Signature signature) {
                T artifact = artifacts.remove(signature);
                publicationToSign.removeDerivedArtifact(artifact);
            }
        });
        return signTask;
    }

    String determineSignTaskNameForPublication(Publication publication) {
        return "sign" + capitalize((CharSequence) publication.getName()) + "Publication";
    }

    private Sign createSignTaskFor(CharSequence name, Action<Sign> taskConfiguration) {
        Sign signTask = project.getTasks().create("sign" + capitalize(name), Sign.class, taskConfiguration);
        addSignaturesToConfiguration(signTask, getConfiguration());
        return signTask;
    }

    protected Object addSignaturesToConfiguration(Sign task, final Configuration configuration) {
        task.getSignatures().all(new Action<Signature>() {
            @Override
            public void execute(Signature sig) {
                configuration.getArtifacts().add(sig);
            }
        });
        return task.getSignatures().whenObjectRemoved(new Action<Signature>() {
            @Override
            public void execute(Signature sig) {
                configuration.getArtifacts().remove(sig);
            }
        });
    }

    /**
     * Digitally signs the publish artifacts, generating signature files alongside them.
     *
     * <p>The project's default signatory and default signature type from the {@link SigningExtension signing settings} will be used to generate the signature. The returned {@link SignOperation sign
     * operation} gives access to the created signature files. <p> If there is no configured default signatory available, the sign operation will fail.
     *
     * @param publishArtifacts The publish artifacts to sign
     * @return The executed {@link SignOperation sign operation}
     */
    public SignOperation sign(final PublishArtifact... publishArtifacts) {
        return doSignOperation(new Action<SignOperation>() {
            @Override
            public void execute(SignOperation operation) {
                operation.sign(publishArtifacts);
            }
        });
    }

    /**
     * Digitally signs the files, generating signature files alongside them.
     *
     * <p>The project's default signatory and default signature type from the {@link SigningExtension signing settings} will be used to generate the signature. The returned {@link SignOperation sign
     * operation} gives access to the created signature files. <p> If there is no configured default signatory available, the sign operation will fail.
     *
     * @param files The files to sign.
     * @return The executed {@link SignOperation sign operation}.
     */
    public SignOperation sign(final File... files) {
        return doSignOperation(new Action<SignOperation>() {
            @Override
            public void execute(SignOperation operation) {
                operation.sign(files);
            }
        });
    }

    /**
     * Digitally signs the files, generating signature files alongside them.
     *
     * <p>The project's default signatory and default signature type from the {@link SigningExtension signing settings} will be used to generate the signature. The returned {@link SignOperation sign
     * operation} gives access to the created signature files. <p> If there is no configured default signatory available, the sign operation will fail.
     *
     * @param classifier The classifier to assign to the created signature artifacts.
     * @param files The publish artifacts to sign.
     * @return The executed {@link SignOperation sign operation}.
     */
    public SignOperation sign(final String classifier, final File... files) {
        return doSignOperation(new Action<SignOperation>() {
            @Override
            public void execute(SignOperation operation) {
                operation.sign(classifier, files);
            }
        });
    }

    /**
     * Creates a new {@link SignOperation sign operation} using the given closure to configure it before executing it.
     *
     * <p>The project's default signatory and default signature type from the {@link SigningExtension signing settings} will be used to generate the signature. The returned {@link SignOperation sign
     * operation} gives access to the created signature files. <p> If there is no configured default signatory available (and one is not explicitly specified in this operation's configuration), the
     * sign operation will fail.
     *
     * @param closure The configuration of the {@link SignOperation sign operation}.
     * @return The executed {@link SignOperation sign operation}.
     */
    public SignOperation sign(Closure closure) {
        return doSignOperation(closure);
    }

    /**
     * Signs the POM artifact for the given Maven deployment.
     *
     * <p>You can use this method to sign the generated POM when publishing to a Maven repository with the Maven plugin. </p>
     * <pre class='autoTested'>
     * uploadArchives {
     *   repositories {
     *     mavenDeployer {
     *       beforeDeployment { MavenDeployment deployment -&gt;
     *         signing.signPom(deployment)
     *       }
     *     }
     *   }
     * }
     * </pre>
     * <p>You can optionally provide a configuration closure to fine tune the {@link SignOperation sign
     * operation} for the POM.</p> <p> If {@link #isRequired()} is false and the signature cannot be generated (e.g. no configured signatory), this method will silently do nothing. That is, a
     * signature for the POM file will not be uploaded.
     * <p>
     * <b>Note:</b> Signing the generated POM file generated by the Maven Publishing plugin is currently not supported. Future versions of Gradle might add this functionality.
     *
     * @param mavenDeployment The deployment to sign the POM of
     * @param closure the configuration of the underlying {@link SignOperation sign operation} for the POM (optional)
     * @return the generated signature artifact
     */
    public Signature signPom(final MavenDeployment mavenDeployment, final Closure closure) {
        SignOperation signOperation = doSignOperation(new Action<SignOperation>() {
            @Override
            public void execute(SignOperation so) {
                so.sign(mavenDeployment.getPomArtifact());
                so.configure(closure);
            }
        });

        Signature pomSignature = signOperation.getSingleSignature();
        if (!pomSignature.getFile().exists()) {
            // This means that the signature was not required and we couldn't generate the signature
            // (most likely project.required == false and there is no signatory)
            // So just noop
            return null;
        }

        // Have to alter the "type" of the artifact to match what is published
        // See https://issues.gradle.org/browse/GRADLE-1589
        pomSignature.setType("pom." + pomSignature.getSignatureType().getExtension());
        mavenDeployment.addArtifact(pomSignature);
        return pomSignature;
    }

    /**
     * Signs the POM artifact for the given Maven deployment.
     *
     * <p>You can use this method to sign the generated POM when publishing to a Maven repository with the Maven plugin. </p>
     * <pre class='autoTested'>
     * uploadArchives {
     *   repositories {
     *     mavenDeployer {
     *       beforeDeployment { MavenDeployment deployment -&gt;
     *         signing.signPom(deployment)
     *       }
     *     }
     *   }
     * }
     * </pre>
     * <p>You can optionally provide a configuration closure to fine tune the {@link SignOperation sign
     * operation} for the POM.</p> <p> If {@link #isRequired()} is false and the signature cannot be generated (e.g. no configured signatory), this method will silently do nothing. That is, a
     * signature for the POM file will not be uploaded.
     * <p>
     * <b>Note:</b> Signing the generated POM file generated by the Maven Publishing plugin is currently not supported. Future versions of Gradle might add this functionality.
     *
     * @param mavenDeployment The deployment to sign the POM of
     * @return the generated signature artifact
     */
    public Signature signPom(MavenDeployment mavenDeployment) {
        return signPom(mavenDeployment, null);
    }

    protected SignOperation doSignOperation(final Closure setup) {
        return doSignOperation(new Action<SignOperation>() {
            @Override
            public void execute(SignOperation operation) {
                operation.configure(setup);
            }
        });
    }

    protected SignOperation doSignOperation(Action<SignOperation> setup) {
        SignOperation operation = instantiator().newInstance(SignOperation.class);
        addSignatureSpecConventions(operation);
        setup.execute(operation);
        operation.execute();
        return operation;
    }

    private Instantiator instantiator() {
        return ((ProjectInternal) project).getServices().get(Instantiator.class);
    }

    public SignatoryProvider getSignatories() {
        return signatories;
    }

    private Object force(Object maybeCallable) {
        return DeferredUtil.unpack(maybeCallable);
    }
}
