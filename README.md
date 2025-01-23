# FileCrawler
[videos-ye-2025-01-20_23.32.16.webm](https://github.com/user-attachments/assets/689e4843-1a55-4801-8c41-12b4541bfab8)

# FileCrawlerPerLevelWithLatch
[videos-ye-2025-01-23_19.00.42.webm](https://github.com/user-attachments/assets/8ce4fd46-7f56-498e-bcac-677cfbd345df)

Crawl from the root down to all possible directories on per level bases an gather information
for each level of directories. Threads will parse only one level at a time, counting down a
latch to signal that each threads finished working. After latch has reached zero, information
gathered about the current level will be printed to the console and now a next level will be
parsed in the same way until there are no left.
                                                                                              
Two queries will be used to store files on the current level and the next level down.
A count down latch will be created with value equal to the number of threads created for
the current level.
                                                                                              
Each thread will pull a file or directory from the current level queue and store
information about the file or if it is a directory goes through all of its files and
directories on first level and adds them to the next level queue.
                                                                                              
After a thread parses a file or directory, it will repeate the operation again until
current level queue is exhaused. After the queue is exhausted the thread will perform a count
down on the latch and finish the task. When the latch reaches zero, information about the
current level will be outputted to the console.
                                                                                              
Next level queue now becomes a current level queue, a new latch is created and the cycle
is repeated till the next level queue is empty.
