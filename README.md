# FileCrawler
[vid](https://github.com/user-attachments/assets/689e4843-1a55-4801-8c41-12b4541bfab8)

# FileCrawlerPerLevelWithLatch
[vid](https://github.com/user-attachments/assets/f6f64986-a460-49ac-8410-56a2628fb05f)

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

# FileDownloaderWithFutureTask 
[vid](https://github.com/user-attachments/assets/610eb0b0-e2c5-4801-9e90-d6c361b68736)

Using FutureTask implements file downloading logic.
                                                                                                
Creates 15 FutureTasks and runs in separate threads. 
The class DownloadTask represents a runnable responsible to imitate a file downloading process. 
DownloadStatus is used to provide current information about a downloading process of DownloadTask.
                                                                                                
The main thread will use DownloadStatus to read status of each downloading file andsend formatted output to the console.                                                                                                
The DownloadTask can throw a random exception, this will set the status of file downloading to "Error" with an error message" and out put to the console. 
The main thread can randomly cancel the task which will lead to the status "Cancelled"

# BouncingBallWithSemaphore 
[vid](https://github.com/user-attachments/assets/777676ff-cfad-4c0c-b0f6-46692e7e9ee1)

A Grid is created with a box inside it. The balls' positions are randomly generated
as their directions. This box can only contain a limited number of balls. A Semaphore is
used to provide a permit to a ball to access the box, if the box is full, the permit isn't
provided which makes the ball bounce off the side.
                                                                                                  
There is a certain a number of times a ball should bounce off the sides of the box in order to
have an access to it besides a permit from the Semaphore. The same goes for when the ball
is inside the box, it also has to make a certain number of bounces in order to leave it and the
release the permit.

