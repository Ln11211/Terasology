// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.cli.commands

import groovy.json.JsonSlurper
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ObjectId
import org.terasology.cli.options.GitOptions
import org.terasology.cli.util.Constants
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.HelpCommand
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Command(name = "module",
        synopsisSubcommandLabel = "COMMAND", // Default is [COMMAND] indicating optional, but sub command here is required
        subcommands = [
            HelpCommand.class, // Adds standard help options (help as a subcommand, -h, and --help)
            InitCommand.class,
        ], // Note that these Groovy classes *must* start with a capital letter for some reason
        description = "Sub command for interacting with modules")
class ModuleCommand extends ItemCommand {

    private Set<String> fetchedModules = [];

    // This is an example of a subcommand via method - used here so we can directly hit ManagedModule for something module-specific
    // If in an external Refresh.groovy it _could_ specify ManagedModule, but it then could be added later to a non-module and break
    @Override
    @Command(name = "refresh", description = "Refreshes all build.gradle files in module directories")
    void refresh() {
        Constants.ModuleDirectory.eachDir { dir ->
            if (!new File(dir, "module.txt").exists()) {
                println "$dir has no module.txt, it must not want a fresh build.gradle"
                return
            }
            println "In refreshGradle for module $dir - copying in a fresh build.gradle"
            File targetBuildGradle = new File(dir, 'build.gradle')
            targetBuildGradle.delete()
            targetBuildGradle << new File('templates/build.gradle').text
        }
    }

    private void copyInTemplates(File targetDir) {
        // Copy in the template build.gradle for modules
        println "In copyInTemplateFiles for module $targetDir.name - copying in a build.gradle then next checking for module.txt"
        File targetBuildGradle = new File(targetDir, 'build.gradle')
        targetBuildGradle.delete()
        targetBuildGradle << new File('templates/build.gradle').text

        // Copy in the template module.txt for modules (if one doesn't exist yet)
        File moduleManifest = new File(targetDir, 'module.txt')
        if (!moduleManifest.exists()) {
            def moduleText = new File("templates/module.txt").text
            moduleManifest << moduleText.replaceAll('MODULENAME', targetDir.name)
            println CommandLine.Help.Ansi.AUTO.string("@|red WARNING: the module ${targetDir.name} did not have a module.txt! One was created, please review and submit to GitHub|@")
        }

    }

    @Override
    String[] getDependencies(File targetDir, boolean respectExcludedItems = true) {
        def dependencies = []
        File moduleFile = new File(targetDir, "module.txt")
        def slurper = new JsonSlurper()
        def moduleConfig = slurper.parseText(moduleFile.text)
        for (dependency in moduleConfig.dependencies) {
            if (!(respectExcludedItems && Constants.ExcludeModule.contains(dependency.id))) {
                dependencies << dependency.id
            }
        }
        return dependencies
    }

    @Override
    @Command(name = "get", description = "Gets one or more items directly")
    void get(@Mixin GitOptions options,
             @Option(names = ["-r", "-recurse"], description = "recursively fetch modules") boolean recurse,
             @Parameters(paramLabel = "items", arity = "1", description = "Target item(s) to get") List<String> items) {
        String origin = options.resolveOrigin()
        for (module in items) {
            File targetDir = new File(Constants.ModuleDirectory, module)
            def targetUrl = "https://github.com/${origin}/${module}"
            if (fetchedModules.contains(targetUrl)) {
                continue
            }
            if (targetDir.exists()) {
                println CommandLine.Help.Ansi.AUTO.string("@|yellow already retrieved $module - skipping|@")
                continue
            }
            try {
                Git.cloneRepository()
                        .setURI(targetUrl)
                        .setDirectory(targetDir)
                        .call()
                println CommandLine.Help.Ansi.AUTO.string("@|green Retrieving module $module from ${targetUrl}|@")
                fetchedModules << targetUrl;
            } catch (Exception ex) {
                println CommandLine.Help.Ansi.AUTO.string("@|red Unable to clone $module, Skipping: ${ex.getMessage()} |@");
                continue
            }

            copyInTemplates(targetDir)
            if (recurse) {
                def dependencies = getDependencies(targetDir)
                if (dependencies.length > 0) {
                    String[] uniqueDependencies = dependencies - fetchedModules
                    println "After removing dupes already retrieved we have the remaining dependencies left: $uniqueDependencies"
                    if (uniqueDependencies.length > 0) {
                        get(options, true, uniqueDependencies.toList())
                    }
                }
            }
        }
    }

    @Override
    @Command(name = "update-all", description = "update all dependenices")
    void updateAll() {
        Constants.ModuleDirectory.eachDir { dir ->
            String itemName = dir.getName()
            if (!Constants.ExcludeModule.contains(itemName)) {
                update([itemName])
            }
        }
    }

    @Override
    @Command(name = "update", description = "Gets one or more items directly")
    void update(@Parameters(paramLabel = "items", arity = "1", description = "Target item(s) to get") List<String> items) {
        for (module in items) {
            File targetDir = new File(Constants.ModuleDirectory, module)
            Git git = Git.open(targetDir);
            ObjectId oldTree = git.getRepository().resolve("HEAD^{tree}");
            try {
                PullResult result = git.pull().call()

                DiffFormatter df = new DiffFormatter(System.out);
                df.setDiffComparator(RawTextComparator.DEFAULT)
                df.setRepository(git.getRepository())
                df.setBinaryFileThreshold(1000);
                df.setDetectRenames(true)

                if (result.isSuccessful()) {
                    ObjectId newTree = git.getRepository().resolve("HEAD^{tree}");
                    List<DiffEntry> diffs = df.scan(oldTree, newTree);
                    if(diffs.size() > 0) {
                        println CommandLine.Help.Ansi.AUTO.string("@|green Successfully update $module |@")
                    } else {
                        println CommandLine.Help.Ansi.AUTO.string("@|yellow Already updatead $module - Skipping |@")
                    }
                    for(DiffEntry entry : diffs) {
                        df.format(entry)
                    }
                } else {
                    println CommandLine.Help.Ansi.AUTO.string("@|red unable to update with un-commited changes |@")
                    for(DiffEntry entry :git.diff().call()) {
                        df.format(entry)
                    }
                }
                df.close()
            } catch(Exception ex) {
                println CommandLine.Help.Ansi.AUTO.string("@|red Unable to update $module, Skipping: ${ex.getMessage()} |@");
            }
        }
    }
}
