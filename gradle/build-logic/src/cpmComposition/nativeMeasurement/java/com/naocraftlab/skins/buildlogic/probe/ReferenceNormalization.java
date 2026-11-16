package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;

public final class ReferenceNormalization {
    static double factor=1,center,referenceCenter;
    static boolean enabled;
    static String sensitivity="",source;
    static void require(){if(enabled&&(ReferenceBodyHost.appearance==null||!ReferenceBodyHost.appearance.skin().location().equals(source)))throw new AssertionError("numeric exact selected source");}
    public static float padding(){require();return 0.97F*(float)(enabled&&!sensitivity.equals("omit-factor")?factor:1);}
    public static CenteredPlayerPreviewGeometry.EntityTranslation translation(float height,float pitch){
        require();if(!enabled||!sensitivity.equals("recenter"))return CenteredPlayerPreviewGeometry.centeredEntityTranslation(height,pitch);
        double offset=CenteredPlayerPreviewGeometry.modernEntityTranslationY(height)-referenceCenter;double placement=center+offset/factor;
        return new CenteredPlayerPreviewGeometry.EntityTranslation((float)(placement*Math.cos(pitch)),(float)(-placement*Math.sin(pitch)));
    }
}
