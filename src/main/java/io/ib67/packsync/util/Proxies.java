package io.ib67.packsync.util;

import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import org.apache.maven.artifact.versioning.ArtifactVersion;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class Proxies {

    public static ArtifactVersion wrapPrioritized(ArtifactVersion artifactVersion) {
        return (ArtifactVersion) Proxy.newProxyInstance(
                Proxies.class.getClassLoader(),
                new Class[]{ArtifactVersion.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("compareTo") && args.length == 1 && args[0] instanceof ArtifactVersion) {
                        return 1; // Always win
                    }
                    return method.invoke(artifactVersion, args);
                }
        );
    }

    public static IModInfo wrapPrioritized(IModInfo mi) {
        return (IModInfo) Proxy.newProxyInstance(
                Proxies.class.getClassLoader(),
                new Class[]{IModInfo.class},
                (proxy, method, args) -> {
                    var name = method.getName();
                    if ("getVersion".equals(name)) {
                        return wrapPrioritized((ArtifactVersion) method.invoke(mi, args));
                    }
                    return method.invoke(mi, args);
                }
        );
    }

    /**
     * depends on the implementation of net.minecraftforge.fml.loading.UniqueModListBuilder#selectNewestModInfo(Map.Entry)
     *
     * @return wrapped modfile, always win in comparison
     */
    public static IModFile wrapPrioritized(IModFile mf) {
        return (IModFile) Proxy.newProxyInstance(
                Proxies.class.getClassLoader(),
                new Class[]{IModFile.class},
                (proxy, method, args) -> {
                    var name = method.getName();
                    if ("getJarVersion".equals(name)) {
                        return wrapPrioritized((ArtifactVersion) method.invoke(mf, args));
                    } else if ("getModInfos".equals(name)) {
                        var modInfos = new ArrayList<>((List<IModInfo>) method.invoke(mf, args));
                        modInfos.set(0, wrapPrioritized(modInfos.get(0)));
                        return modInfos;
                    }
                    return method.invoke(proxy, args);
                });
    }
}
