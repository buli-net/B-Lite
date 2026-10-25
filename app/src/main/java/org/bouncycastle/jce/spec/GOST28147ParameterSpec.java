package org.bouncycastle.jce.spec;

/**
 * A parameter spec for the GOST-28147 cipher.
 * @deprecated use  org.bouncycastle.jcajce.spec.GOST28147ParameterSpec
 */
    @Deprecated
public class GOST28147ParameterSpec
    extends org.bouncycastle.jcajce.spec.GOST28147ParameterSpec
{
    /**
     * @deprecated
     */
    @Deprecated
    public GOST28147ParameterSpec(
        byte[] sBox)
    {
        super(sBox);
    }

    /**
     * @deprecated
     */
    @Deprecated
    public GOST28147ParameterSpec(
        byte[] sBox,
        byte[] iv)
    {
        super(sBox, iv);

    }

    /**
     * @deprecated
     */
    @Deprecated
    public GOST28147ParameterSpec(
        String  sBoxName)
    {
        super(sBoxName);
    }

    /**
     * @deprecated
     */
    @Deprecated
    public GOST28147ParameterSpec(
        String  sBoxName,
        byte[]  iv)
    {
        super(sBoxName, iv);
    }
}