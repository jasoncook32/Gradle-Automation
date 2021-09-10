/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.api.internal.catalog;

import org.gradle.api.artifacts.ExternalModuleDependencyBundle;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.internal.catalog.AbstractExternalDependencyFactory.BundleFactory;
import org.gradle.api.internal.catalog.AbstractExternalDependencyFactory.PluginFactory;
import org.gradle.api.internal.catalog.AbstractExternalDependencyFactory.VersionFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.plugin.use.PluginDependency;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;

public class VersionCatalogView implements VersionCatalog {

    private final DefaultVersionCatalog config;
    private final ProviderFactory providers;
    private final AbstractExternalDependencyFactory factory;

    @Inject
    public VersionCatalogView(DefaultVersionCatalog config, ProviderFactory providerFactory) {
        this.config = config;
        this.providers = providerFactory;
        this.factory = new DefaultExternalDependencyFactory(config, providerFactory);
    }

    @Override
    public final Optional<Provider<MinimalExternalModuleDependency>> findDependency(String alias) {
        if (config.getDependencyAliases().contains(alias)) {
            return Optional.of(factory.create(alias));
        }
        return Optional.empty();
    }

    @Override
    public final Optional<Provider<ExternalModuleDependencyBundle>> findBundle(String bundle) {
        if (config.getBundleAliases().contains(bundle)) {
            return Optional.of(new BundleFactory(providers, config).createBundle(bundle));
        }
        return Optional.empty();
    }

    @Override
    public final Optional<VersionConstraint> findVersion(String name) {
        if (config.getVersionAliases().contains(name)) {
            return Optional.of(new VersionFactory(providers, config).findVersionConstraint(name));
        }
        return Optional.empty();
    }

    @Override
    public Optional<Provider<PluginDependency>> findPlugin(String alias) {
        if (config.getPluginAliases().contains(alias)) {
            return Optional.of(new PluginFactory(providers, config).createPlugin(alias));
        }
        return Optional.empty();
    }

    @Override
    public final String getName() {
        return config.getName();
    }

    @Override
    public List<String> getDependencyAliases() {
        return config.getDependencyAliases();
    }

    @Override
    public List<String> getBundleAliases() {
        return config.getBundleAliases();
    }

    @Override
    public List<String> getVersionAliases() {
        return config.getVersionAliases();
    }

    @Override
    public List<String> getPluginAliases() {
        return config.getPluginAliases();
    }
}
