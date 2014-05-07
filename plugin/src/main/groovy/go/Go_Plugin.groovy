package go

apply plugin: 'groovy'
import org.gradle.api.file.*
import org.gradle.api.*
import org.gradle.api.tasks.*

// Default to user's GOPATH
//goPlug.goPath = "$System.env.GOPATH"
//goPlug.versionFile = "$System.env.GOPATH" + "/version.txt"
//goPlug.currentProject = "$System.env.PWD"
//goPlug.versionMap = [:]


class GoPlugin implements Plugin<Project> {
    void apply(Project project) {
        
        // DEFAULTS
        project.extensions.create("go",GoPluginExtension)
        project.defaultTasks 'goPlugin_Welcome','createVersionMap'

        // INFORMATIONAL TASKS

        project.task('goPlugin_Welcome') {
            doFirst{
                println "Welcome to the goPlugin, you're settings are as follows:"
                println "  Root Project: $project.rootProject"
                println "  At: $project.rootDir"
                println "  GoPath: $project.go.goPath"
                println "  Version File: $project.go.versionFile"
                println "  Current Project scope: $project.go.currentProject"
            }  
        }

        project.task('printProjectTree') << {
            //FileTree goWorkspace
            FileTree goWorkspace = project.fileTree(dir: ("$project.go.goPath"+"/src"))
            goWorkspace.include '**/.git'
            println "  Go Projects in this workspace:"
            goWorkspace.visit {gitproject ->
                def t = new File("$gitproject".replace('file ','').replace("'",''))
                def foundGoFile = false
                if (t.isDirectory()){
                    t.eachFileMatch(~/^\.git$/){
                        foundGoFile = true
                    }
                    if (foundGoFile){
                        println "    $t"
                    }
                }
            }
        }

        project.task('printImportList') << {
            println project.go.importList
        }

        // INITIALIZATION TASKS

        project.task('findImports') << {
            FileTree goWorkspace
            goWorkspace = project.fileTree(dir: project.go.currentProject)
            // We only care about go files
            goWorkspace.include '**/*.go'
            //println "Here are some imports"
            def list = []
            goWorkspace.visit {gofile ->
                if ("$gofile.relativePath".endsWith('.go')){ 
                    // Have to translate between gradle object and groovy File object, so remove the 'file ' at start of string
                    def t = new File("$gofile".replace('file ','').replace("'",'')).text
                    def simple_depends = t.findAll(~/import\s+\".+\"/)
                    simple_depends.each{list.add(it.find('\".+\"').replace('"',''))}
                    def compound_depends = String.valueOf(t.replace('\n','').find(~/import\s\(.+?\)/))
                    compound_depends.findAll(~/\".+?\"/).each{list.add(it.replace('"',''))}
                    
                }
            }

            project.go.importList = list.sort() as Set
        }

        project.task('createVersionMap') << {
            def File vf
            vf = project.file(project.go.versionFile)
            // Tab delimited file with projectName<tab>gitSHA on each line
            // Example: github.com/smartystreets/goconvey/web/server/system    2124ee55e7c5737f5ea4a7744b58069c1499b8cc
            if (vf.isFile()){
                vf.eachLine{ sourceVersion ->
                    project.go.versionMap[sourceVersion.tokenize("\t")[0]] = sourceVersion.tokenize("\t")[1]
                }
            }
            println "The projects you wish versioned, with the git commits you want:"
            println project.go.versionMap
        }

        // ACTION TASKS

        project.task('executeCheckouts') << {
            project.go.versionMap.each{ projectToUpdate ->
                def folder = "$project.go.goPath"+"/src/"+"$projectToUpdate.key"
                def gitVersion = "$projectToUpdate.value"
                def newTask = "checkout_"+"$projectToUpdate.value"
                println "Checking out commit: $gitVersion, on project: $folder"
                project.task("checkout_$gitVersion",type: checkout){
                    gitRepo ="$folder"
                    sha = "$gitVersion"
                    checkItOut()
                }
                project.tasks["checkout_$gitVersion"].execute()
            }
        }

        project.task('clean') 
        project.task('build') << {
            def File goWorkspace
            goWorkspace = project.file(("$project.go.goPath"+"/src"))
            goWorkspace.eachDir {t ->
                def foundGoFile = false
                if (t.isDirectory()){
                    t.eachFileMatch(~/.+\.go$/){
                        foundGoFile = true
                    }
                    if (foundGoFile){
                        //println "  Installing "+"$t.absolutePath"-"$project.go.goPath"-"/src/"
                        project.tasks["goBuild_"+"$t.absolutePath"-"$project.go.goPath"-"/src/"].execute()
                    }
                }
            }
        }

        project.task('install') << {
            def File goWorkspace
            goWorkspace = project.file(("$project.go.goPath"+"/src"))
            goWorkspace.eachDir {t ->
                def foundGoFile = false
                if (t.isDirectory()){
                    t.eachFileMatch(~/.+\.go$/){
                        foundGoFile = true
                    }
                    if (foundGoFile){
                        //println "  Installing "+"$t.absolutePath"-"$project.go.goPath"-"/src/"
                        project.tasks["goInstall_"+"$t.absolutePath"-"$project.go.goPath"-"/src/"].execute()
                    }
                }
            }
        }
        
        project.task('test') << {
            FileTree goWorkspace
            goWorkspace = project.fileTree(dir: ("$project.go.goPath" + "/src"))
            //goWorkspace = project.fileTree(dir: ("$project.go.currentProject"))
            goWorkspace.include '**/*_test.go'
            goWorkspace.visit {test_file ->
                def t = new File("$test_file".replace('file ','').replace("'",''))
                def foundATestFile = false
                if (t.isDirectory()){
                    t.eachFileMatch(~/.+test\.go$/){
                        foundATestFile = true
                    }
                    if (foundATestFile){
                        //project.tasks["goTest_"+"$t.absolutePath"-"$project.go.goPath"-"/src/"].execute()
                        project.task("test_$t.absolutePath"-"$project.go.goPath"-"/src/",type: goTool){
                            subTool = "test"
                            projectPath = "$t.absolutePath"
                            runGoTool()
                        }
                        project.tasks[("test_$t.absolutePath"-"$project.go.goPath"-"/src/")].execute()
                    }
                }
            }
        }

        // RULES FOR SPECIAL CASE EXECUTION

        project.tasks.addRule("Pattern: goGet_<ID>"){ String taskName ->
            if (taskName.startsWith("goGet_")){
                project.task(taskName,type:Exec){
                    println "  Getting $taskName"-"goGet_"+" in workspace $project.go.goPath"
                    workingDir project.go.goPath
                    executable 'go'
                    args 'get', (taskName - 'goGet_')
                }
            }
        }

        project.tasks.addRule("Pattern: goInstall_<ID>"){ String taskName ->
            if (taskName.startsWith("goInstall_")){
                project.task(taskName,type:Exec){
                    println "  Installing $taskName"-"goInstall_"+" in workspace $project.go.goPath"
                    workingDir project.go.goPath
                    executable 'go'
                    args 'install', (taskName - 'goInstall_')
                }
            }
        }

        project.tasks.addRule("Pattern: goBuild_<ID>"){ String taskName ->
            if (taskName.startsWith("goBuild_")){
                project.task(taskName,type:Exec){
                    println "  Building $taskName"-"goBuild_"+" in workspace $project.go.goPath"
                    workingDir project.go.goPath
                    executable 'go'
                    args 'build', (taskName - 'goBuild_')
                }
            }
        }

        project.tasks.addRule("Pattern: goRun_<ID>"){ String taskName ->
            if (taskName.startsWith("goRun_")){
                project.task(taskName,type:Exec){
                    println "  Running $taskName"-"goRun_"+" in workspace $project.go.goPath"
                    workingDir project.go.goPath
                    executable 'go'
                    args 'run', (taskName - 'goRun_')
                }
            }
        }

        project.tasks.addRule("Pattern: goTest_<ID>"){ String taskName ->
            if (taskName.startsWith("goTest_")){
                project.task(taskName,type:Exec){
                    println "  Testing $taskName"-"goTest_"+" in workspace $project.go.goPath"
                    workingDir project.go.goPath
                    executable 'go'
                    args 'test', (taskName - 'goTest_')
                }
            }
        }

        // TASK DEPENDENCIES

        
        project.createVersionMap.dependsOn project.goPlugin_Welcome
        project.executeCheckouts.dependsOn project.createVersionMap
        project.printImportList.dependsOn project.findImports
    }
}

class checkout extends Exec {
    String sha = ''
    String gitRepo = ''
    String option = ''

    @TaskAction
    def void checkItOut(){
        workingDir = "$gitRepo"
        executable = 'git'
        if (option == ''){
            args "checkout", "$sha"
        }
        else{
            args "$option", "checkout", "$sha"
        }
        
    }
}

class goTool extends Exec {
    String projectPath = ''
    String subTool = ''
    String option = ''

    @TaskAction
    def void runGoTool(){
        workingDir = "$projectPath"
        executable = 'go'
        if (option == ''){
            args "$subTool"
        }
        else{
            args "$option", "subTool"
        }
        
    }
}