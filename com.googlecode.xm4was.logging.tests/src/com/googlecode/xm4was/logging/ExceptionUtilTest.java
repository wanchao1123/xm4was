package com.googlecode.xm4was.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;

import org.junit.Test;

import com.ibm.ejs.ras.RasHelper;

public class ExceptionUtilTest {
    @Test
    public void testFormatSimple() throws Exception {
        try {
            new MyClass().method3();
        } catch (RuntimeException ex) {
            LineAppenderImpl appender = new LineAppenderImpl();
            ExceptionUtil.formatStackTrace(ExceptionUtil.process(ex), appender);
            appender.assertLine("java\\.lang\\.RuntimeException: Test");
            appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.MyClass\\.method1\\([0-9]+\\)");
            appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.MyClass\\.method2\\([0-9]+\\)");
            appender.assertLine("Wrapped by: java\\.lang\\.RuntimeException: Wrapper");
            appender.assertLine(" \\+ com\\.googlecode\\.xm4was\\.logging\\.MyClass\\.wrapException\\([0-9]+\\)");
            appender.assertLine(" \\+ com\\.googlecode\\.xm4was\\.logging\\.MyClass\\.method2\\([0-9]+\\)");
            appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.MyClass\\.method3\\([0-9]+\\)");
            appender.assertLine("Wrapped by: java\\.lang\\.RuntimeException: Another wrapper");
            appender.assertLine(" \\+ com\\.googlecode\\.xm4was\\.logging\\.MyClass\\.method3\\([0-9]+\\)");
            appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.ExceptionUtilTest\\.testFormatSimple\\([0-9]+\\)");
        }
    }
    
    @Test
    public void testFormatReflectiveInvocation() throws Exception {
        try {
            MyClass.class.getDeclaredMethod("method1").invoke(new MyClass());
        } catch (InvocationTargetException ex) {
            LineAppenderImpl appender = new LineAppenderImpl();
            ExceptionUtil.formatStackTrace(ExceptionUtil.process(ex), appender);
            appender.assertLine("java\\.lang\\.RuntimeException: Test");
            appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.MyClass\\.method1\\([0-9]+\\)");
            appender.assertLine("Wrapped by: java\\.lang\\.reflect\\.InvocationTargetException");
            // Normally there would be a couple of additional frames here, but they are suppressed by
            // the formatter.
            appender.assertLine(" \\| java\\.lang\\.reflect\\.Method\\.invoke\\([0-9]+\\)");
            appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.ExceptionUtilTest\\.testFormatReflectiveInvocation\\([0-9]+\\)");
        }
    }
    
    @Test
    public void testFormatProxy() throws Exception {
        try {
            DummyInterface proxy = (DummyInterface)Proxy.newProxyInstance(ExceptionUtilTest.class.getClassLoader(),
                    new Class<?>[] { DummyInterface.class }, new DummyInvocationHandler());
            proxy.throwException();
        } catch (RuntimeException ex) {
            LineAppenderImpl appender = new LineAppenderImpl();
            ExceptionUtil.formatStackTrace(ExceptionUtil.process(ex), appender);
            appender.assertLine("java\\.lang\\.RuntimeException: Test exception");
            appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.DummyInvocationHandler\\.invoke\\([0-9]+\\)");
            appender.assertLine(" \\| \\[proxy\\]\\.throwException");
            appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.ExceptionUtilTest\\.testFormatProxy\\([0-9]+\\)");
        }
    }
    
    /**
     * Tests the case where the wrapper has no message. The classic stack trace would contain the
     * message of the wrapped exception twice, but we don't repeat the message.
     * 
     * @throws Exception
     */
    @Test
    public void testNoDuplicateMessage() throws Exception {
        IOException rootCause = new IOException("Some exception message");
        RuntimeException ex = new RuntimeException(rootCause);
        LineAppenderImpl appender = new LineAppenderImpl();
        ExceptionUtil.formatStackTrace(ExceptionUtil.process(ex), appender);
        appender.assertLine("java\\.io\\.IOException: Some exception message");
        appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.ExceptionUtilTest\\.testNoDuplicateMessage\\([0-9]+\\)");
        appender.assertLine("Wrapped by: java\\.lang\\.RuntimeException");
        appender.assertLine(" \\+ com\\.googlecode\\.xm4was\\.logging\\.ExceptionUtilTest\\.testNoDuplicateMessage\\([0-9]+\\)");
    }
    
    /**
     * Tests the case where the wrapper has the same type and message as the wrapped exception (but
     * doesn't have the same identity). In previous XM4WAS versions this caused a
     * {@link StringIndexOutOfBoundsException}.
     * 
     * @throws Exception
     */
    @Test
    public void testSameCause() throws Exception {
        Error cause = new Error("MESSAGE");
        Error ex = new Error("MESSAGE", cause);
        LineAppenderImpl appender = new LineAppenderImpl();
        ExceptionUtil.formatStackTrace(ExceptionUtil.process(ex), appender);
        appender.assertLine("java\\.lang\\.Error: MESSAGE");
        appender.assertLine(" \\| com\\.googlecode\\.xm4was\\.logging\\.ExceptionUtilTest\\.testSameCause\\([0-9]+\\)");
        appender.assertLine("Wrapped by: java\\.lang\\.Error: MESSAGE");
        appender.assertLine(" \\+ com\\.googlecode\\.xm4was\\.logging\\.ExceptionUtilTest\\.testSameCause\\([0-9]+\\)");
    }
    
    private static String throwableToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, false);
        t.printStackTrace(out);
        out.flush();
        return sw.toString();
    }
    
    @Test
    public void testParse() throws Exception {
        try {
            new MyClass().method3();
        } catch (RuntimeException ex) {
            ThrowableInfo[] throwables = ExceptionUtil.parse(throwableToString(ex));
            assertNotNull(throwables);
            ThrowableInfo[] expected = ExceptionUtil.process(ex);
            assertEquals(expected.length, throwables.length);
            for (int i=0; i<expected.length; i++) {
                assertEquals(expected[i].getMessage(), throwables[i].getMessage());
                StackTraceElement[] stackTrace = throwables[i].getStackTrace();
                StackTraceElement[] expectedStackTrace = expected[i].getStackTrace();
                assertEquals(expectedStackTrace.length, stackTrace.length);
                for (int j=0; j<expectedStackTrace.length; j++) {
                    // The equals method of StackTraceElement doesn't work here because the file name
                    // is lost if line==-2 (native method)
                    StackTraceElement frame = stackTrace[j];
                    StackTraceElement expectedFrame = expectedStackTrace[j];
                    assertEquals(expectedFrame.getClassName(), frame.getClassName());
                    assertEquals(expectedFrame.getMethodName(), frame.getMethodName());
                    assertEquals(expectedFrame.getLineNumber(), frame.getLineNumber());
                    if (expectedFrame.getLineNumber() > 0) {
                        assertEquals(expectedFrame.getFileName(), frame.getFileName());
                    }
                }
            }
        }
    }

    @Test
    public void testParseWithRasHelperNestedThrowables() throws Exception {
        SQLException ex = new SQLException("ex1");
        ex.setNextException(new SQLException("ex2"));
        String formattedThrowable = RasHelper.throwableToString(new Error(ex));
        ThrowableInfo[] parsedThrowables = ExceptionUtil.parse(formattedThrowable);
        assertNotNull(parsedThrowables);
        assertEquals(3, parsedThrowables.length);
        assertEquals("java.lang.Error: java.sql.SQLException: ex1", parsedThrowables[0].getMessage());
        assertEquals("java.sql.SQLException: ex1", parsedThrowables[1].getMessage());
        assertEquals("java.sql.SQLException: ex2", parsedThrowables[2].getMessage());
    }
    
    @Test
    public void testParseWithMultilineMessage() throws Exception {
        Throwable t = new RuntimeException("This is a\nmulti-line message");
        ThrowableInfo[] parsedThrowables = ExceptionUtil.parse(throwableToString(t));
        assertNotNull(parsedThrowables);
        assertEquals(1, parsedThrowables.length);
        assertEquals("java.lang.RuntimeException: This is a\nmulti-line message", parsedThrowables[0].getMessage());
    }
}
