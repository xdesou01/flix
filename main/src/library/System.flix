/*
 * Copyright 2021 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
mod System {

    ///
    /// Operations on the Standard Input Stream.
    ///
    mod StdIn {

        ///
        /// Returns an iterator over lines from the standard input stream.
        ///
        /// The iterator ends when the stream is closed or when an error occurs.
        ///
        /// When the iterator is garbage collected the standard input stream is closed.
        /// Thus, the `readLines` function should not be called more than once per program.
        ///
        /// See also `Console.readLine` for reading from the console.
        ///
        pub def readLines(rc: Region[r]): Iterator[String, IO, r] \ { r, IO } =
            import static get java.lang.System.in: ##java.io.InputStream \ IO as getSystemIn;
            import new java.io.InputStreamReader(##java.io.InputStream): ##java.io.InputStreamReader \ IO as newInputStream;
            import new java.io.BufferedReader(##java.io.Reader): ##java.io.BufferedReader \ IO as newBufferedReader;
            // note: BufferedReader.readLine can block
            import java.io.BufferedReader.readLine(): String \ IO as brReadLine;

            let br = newBufferedReader(checked_cast(newInputStream(getSystemIn()))); // up-cast to superclass
            let nextLine = ref None @ rc;
            let next = () -> {
                let l = match deref nextLine {
                    case Some(line) => if (Object.isNull(line)) None else Some(line)
                    case None =>
                        let n = brReadLine(br);
                        nextLine := Some(n);
                        if (Object.isNull(n)) None else Some(n)
                };
                nextLine := None;
                l
            };
            Iterator.iterate(rc, next)

    }

    ///
    /// Operations on the Standard Output Stream.
    ///
    mod StdOut {

        ///
        /// Prints the given string `s` to the standard output stream.
        ///
        pub def print(s: String): Unit \ IO =
            import java.io.PrintStream.print(String): Unit \ IO;
            s |> print(getOutputStream())

        ///
        /// Prints the given string `s` and a newline to the standard output stream.
        ///
        pub def println(s: String): Unit \ IO =
            import java.io.PrintStream.println(String): Unit \ IO;
            s |> println(getOutputStream())

        ///
        /// Prints a newline to the standard output stream.
        ///
        pub def newLine(): Unit \ IO =
            import java.io.PrintStream.println(): Unit \ IO;
            getOutputStream() |> println

        ///
        /// Flushes the standard output stream.
        ///
        pub def flush(): Unit \ IO =
            import java.io.PrintStream.flush(): Unit \ IO;
            getOutputStream() |> flush

        ///
        /// Returns the standard output stream.
        ///
        def getOutputStream(): ##java.io.PrintStream \ IO =
            import static get java.lang.System.out: ##java.io.PrintStream \ IO as getOut;
            getOut()

    }

    ///
    /// Operations on the Standard Error Stream.
    ///
    mod StdErr {

        ///
        /// Prints the given string `s` to the standard error stream.
        ///
        pub def print(s: String): Unit \ IO =
            import java.io.PrintStream.print(String): Unit \ IO;
            s |> print(getErrorStream())

        ///
        /// Prints the given string `s` and a newline to the standard error stream.
        ///
        pub def println(s: String): Unit \ IO =
            import java.io.PrintStream.println(String): Unit \ IO;
            s |> println(getErrorStream())

        ///
        /// Prints a newline to the standard error stream.
        ///
        pub def newLine(): Unit \ IO =
            import java.io.PrintStream.println(): Unit \ IO;
            getErrorStream() |> println

        ///
        /// Flushes the standard error stream.
        ///
        pub def flush(): Unit \ IO =
            import java.io.PrintStream.flush(): Unit \ IO;
            getErrorStream() |> flush

        ///
        /// Returns the standard error stream.
        ///
        def getErrorStream(): ##java.io.PrintStream \ IO =
            import static get java.lang.System.err: ##java.io.PrintStream \ IO as getErr;
            getErr()

    }

}
