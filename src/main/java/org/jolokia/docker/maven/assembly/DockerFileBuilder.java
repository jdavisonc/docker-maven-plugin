package org.jolokia.docker.maven.assembly;

import java.io.File;
import java.io.IOException;
import java.util.*;

import com.google.common.base.Joiner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.jolokia.docker.maven.config.Arguments;
import static org.jolokia.docker.maven.assembly.DockerFileKeyword.*;

/**
 * Create a dockerfile
 *
 * @author roland
 * @since 17.04.14
 */
public class DockerFileBuilder {

    private static final Joiner JOIN_ON_COMMA = Joiner.on("\",\"");

    // Base image to use as from
    private String baseImage;

    // Maintainer of this image
    private String maintainer = "docker-maven-plugin@jolokia.org";

    // Workdir
    private String workdir = null;

    // Basedir to be export
    private String basedir = "/maven";

    private Arguments entryPoint;
    private Arguments cmd;

    private Boolean exportBasedir = null;

    // User under which the files should be added
    private String user;

    // List of files to add. Source and destination follow except that destination
    // in interpreted as a relative path to the exportDir
    // See also http://docs.docker.io/reference/builder/#add
    private List<AddEntry> addEntries = new ArrayList<>();

    // list of ports to expose and environments to use
    private List<Integer> ports = new ArrayList<>();

    // list of RUN Commands to run along with image build see issue #191 on github
    private List<String> runCmds = new ArrayList<>();

// environment

    private Map<String,String> envEntries = new HashMap<>();

    // image labels
    private Map<String, String> labels = new HashMap<>();

    // exposed volumes
    private List<String> volumes = new ArrayList<>();

    /**
     * Create a DockerFile in the given directory
     * @param  destDir directory where to store the dockerfile
     * @return the full path to the docker file
     * @throws IOException if writing fails
     */
    public File write(File destDir) throws IOException {
        File target = new File(destDir,"Dockerfile");
        FileUtils.fileWrite(target, content());
        return target;
    }

    /**
     * Create a Dockerfile following the format described in the
     * <a href="http://docs.docker.io/reference/builder/#usage">Docker reference manual</a>
     *
     * @return the dockerfile create
     * @throws IllegalArgumentException if no src/dest entries have been added
     */
    public String content() throws IllegalArgumentException {

        StringBuilder b = new StringBuilder();
        
        FROM.addTo(b,baseImage != null ? baseImage : DockerAssemblyManager.DEFAULT_DATA_BASE_IMAGE);
        MAINTAINER.addTo(b,maintainer);

        addEnv(b);
        addLabels(b);
        addPorts(b);

        addVolumes(b);
        addEntries(b);
        addRun(b);
        addWorkdir(b);
        addCmd(b);
        addEntryPoint(b);

        return b.toString();
    }

    private void addWorkdir(StringBuilder b) {
        if (workdir != null) {
            WORKDIR.addTo(b, workdir);
        }
    }

    private void addEntryPoint(StringBuilder b){
        if (entryPoint != null) {
            buildArguments(b, ENTRYPOINT, entryPoint);
        }
    }

    private void addCmd(StringBuilder b){
        if (cmd != null) {
            buildArguments(b, CMD, cmd);
        }
    }

    private static void buildArguments(StringBuilder b, DockerFileKeyword key, Arguments arguments) {
        String arg;
        if (arguments.getShell() != null) {
            arg = arguments.getShell();
        } else {
            arg = "[\""  + JOIN_ON_COMMA.join(arguments.getExec()) + "\"]";
        }
        key.addTo(b, arg);
    }
    
    private void addEntries(StringBuilder b) {
        List<String> destinations = new ArrayList<>();
        for (AddEntry entry : addEntries) {
            String dest =  (basedir.equals("/") ? "" : basedir) +  "/" + entry.destination;
            destinations.add(dest);
            COPY.addTo(b,entry.source,dest);
        }
        if (user != null) {
            String[] userParts = StringUtils.split(user, ":");
            String userArg = userParts.length > 1 ? userParts[0] + ":" + userParts[1] : userParts[0];
            String chmod = "[\"chown\", \"-R\", \"" + userArg + "\",\"" +
                         StringUtils.join(destinations.iterator(), "\",\"") + "\"]\n";
            if (userParts.length > 2) {
                USER.addTo(b,"root");
                RUN.addTo(b, chmod);
                USER.addTo(b, userParts[2]);
            } else {
                RUN.addTo(b, chmod);
            }
        }
    }

    private void addEnv(StringBuilder b) {
        addMap(b,ENV,envEntries);
    }

    private void addLabels(StringBuilder b) {
        addMap(b,LABEL,labels);
    }

    private void addMap(StringBuilder b,DockerFileKeyword keyword, Map<String,String> map) {
        if (map != null && map.size() > 0) {
            String entries[] = new String[map.size()];
            int i = 0;
            for (Map.Entry<String, String> entry : map.entrySet()) {
                entries[i++] = quote(entry.getKey()) + "=" + quote(entry.getValue());
            }
            keyword.addTo(b, entries);
        }
    }

    private String quote(String value) {
        return StringUtils.quoteAndEscape(value,'"');
    }

    private void addPorts(StringBuilder b) {
        if (ports.size() > 0) {
            String[] portsS = new String[ports.size()];
            int i = 0;
            for (Integer port : ports) {
                portsS[i++] = port.toString();
            }
            EXPOSE.addTo(b, portsS);
        }
    }

	private void addRun(StringBuilder b) {
		for (String run : runCmds) {
            RUN.addTo(b,run);
		}
	}

    private void addVolumes(StringBuilder b) {
        if (exportBasedir != null ? exportBasedir : baseImage == null) {
            addVolume(b, basedir);
        }

        for (String volume : volumes) {
            addVolume(b, volume);
        }
    }
    
    private void addVolume(StringBuilder buffer, String volume) {
        while (volume.endsWith("/")) {
            volume = volume.substring(0, volume.length() - 1);
        }
        // don't export '/'
        if (volume.length() > 0) {
            VOLUME.addTo(buffer,"[\"" + volume + "\"]");
        }
    }
    
    // ==========================================================================
    // Builder stuff ....
    public DockerFileBuilder() {}

    public DockerFileBuilder baseImage(String baseImage) {
        if (baseImage != null) {
            this.baseImage = baseImage;
        }
        return this;
    }

    public DockerFileBuilder maintainer(String maintainer) {
        this.maintainer = maintainer;
        return this;
    }

    public DockerFileBuilder workdir(String workdir) {
        this.workdir = workdir;
        return this;
    }

    public DockerFileBuilder basedir(String dir) {
        if (dir != null) {
            basedir = dir;
        }
        return this;
    }

    public DockerFileBuilder cmd(Arguments cmd) {
        cmd.validate();
        this.cmd = cmd;
        return this;
    }

    public DockerFileBuilder entryPoint(Arguments entryPoint) {
        entryPoint.validate();
        this.entryPoint = entryPoint;
        return this;
    }

    public DockerFileBuilder user(String user) {
        this.user = user;
        return this;
    }

    public DockerFileBuilder add(String source, String destination) {
        this.addEntries.add(new AddEntry(source, destination));
        return this;
    }

    public DockerFileBuilder expose(List<String> ports) {
        if (ports != null) {
            for (String port : ports) {
                if (port != null) {
                    try {
                        this.ports.add(Integer.parseInt(port));
                    } catch (NumberFormatException exp) {
                        throw new IllegalArgumentException("Non numeric port " + port + " specified in port mapping",exp);
                    }
                }
            }
        }
        return this;
    }

    /**
     * Adds the RUN Commands within the build image section
     * @param runCmds
     * @return
     */
    public DockerFileBuilder run(List<String> runCmds) {
        if (runCmds != null) {
            for (String cmd : runCmds) {
                if (!StringUtils.isEmpty(cmd)) {
                    this.runCmds.add(cmd);
                }
            }
        }
        return this;
    }

    public DockerFileBuilder exportBasedir(Boolean exportBasedir) {
        this.exportBasedir = exportBasedir;
        return this;
    }
    
    public DockerFileBuilder env(Map<String, String> values) {
        if (values != null) {
            this.envEntries.putAll(values);
            validateMap(envEntries);
        }
        return this;
    }

    public DockerFileBuilder labels(Map<String,String> values) {
        if (values != null) {
            this.labels.putAll(values);
            validateMap(labels);
        }
        return this;
    }

    public DockerFileBuilder volumes(List<String> volumes) {
        if (volumes != null) {
           this.volumes.addAll(volumes);
        }
        return this;
    }
    
    private void validateMap(Map<String, String> env) {
        for (Map.Entry<String,String> entry : env.entrySet()) {
            if (entry.getValue() == null || entry.getValue().length() == 0) {
                throw new IllegalArgumentException("Environment variable '" +
                                                   entry.getKey() + "' must not be null or empty if building an image");
            }
        }
    }

    // All entries required, destination is relative to exportDir
    private static final class AddEntry {
        private String source,destination;

        private AddEntry(String src, String dest) {
            source = src;

            // Strip leading slashes
            destination = dest;

            // squeeze slashes
            while (destination.startsWith("/")) {
                destination = destination.substring(1);
            }
        }
    }

}
