import java.io.*;

/**
 * Unit tests are in {@see TestMyFileSystem}.
 */
public class MyFileSystem implements FileSystem {
    Disk       disk       = new Disk();
    FileTable  fileTable  = new FileTable();
    SuperBlock superBlock = new SuperBlock();
    FreeMap    freeMap;

    /**
     * Reading from or writing to a file.
     */
    private enum MODE { w, r };

    /**
     * Construct a new FileSystem. You are responsible for calling
     * formatDisk on the new FileSystem if necessary.
     */
    public MyFileSystem() throws IOException {
        disk.read(0, superBlock);
        initFreeMap();
    }
    
    public int formatDisk(int size, int isize) throws IOException {
        // The total size of the file system cannot be larger than the
        // maximum size of the disk.
        //
        if(size > Disk.NUM_BLOCKS) {
            System.err.println("Size exceeds disk size of " + Disk.NUM_BLOCKS);
            return -1;
        }
        
        // Calculate the number of blocks needed for the freemap (may
        // be 0 if the entire free map fits within the superblock.
        //
        int extra = (size - isize - 1) - superBlock.freeMap.length * 8;
        int msize = (int)Math.max(0, Math.ceil(extra / 8.0 / Disk.BLOCK_SIZE));
        
        // We require that the size of the metadata not exceed the
        // size of the file system.
        //
        if(size - msize - isize - 1 < 0) {
            System.err.println("Metadata will not fit in file system");
            return -1;
        }

        // Initialize and write the superblock.
        superBlock.size  = size;
        superBlock.isize = isize;
        superBlock.msize = msize;
        disk.write(0, superBlock);

        // Write empty FreeMapBlocks (if needed) and InodeBlocks, with
        // FreeMapBlocks immediately following the SuperBlock and
        // InodeBlocks immediately following the FreeMapBlocks.
        //
        if(superBlock.mblock0() > 0)
            for(int i = superBlock.mblock0(); i < superBlock.iblock0(); ++i)
                disk.write(i, new FreeMapBlock());
        for(int i = superBlock.iblock0(); i < superBlock.dblock0(); ++i)
            disk.write(i, new InodeBlock());

        // Set up the free map again (because we changed file system
        // metadata since the constructor was called).
        //
        initFreeMap();

        return 0;
    }
    
    public int shutdown() throws IOException {
        // Save any free map blocks that haven't been written
        freeMap.save();

        // Close any open files
        for(int fd = 0; fd < FileTable.MAX_FILES; ++fd)
            if(fileTable.isValid(fd))
                close(fd);

        // Stop the disk and end
        disk.stop(false);
        return 0;
    }
    
    public int create() throws IOException {
        // Try to get a free file descriptor.
        //
        int fd = fileTable.allocate();
        if(fd < 0)
            return -1;

        // Try to find an inode for the new file.
        //
        InodeBlock block = new InodeBlock();
        int inumber = 1; // inumbers start at 1, not 0
        for(int n = superBlock.iblock0(); n < superBlock.dblock0(); ++n) {
            disk.read(n, block);
            for(int o = 0; o < InodeBlock.COUNT; ++o, ++inumber) {
                if(block.inodes[o].flags == 0) {
                    block.inodes[o].allocate();
                    fileTable.add(block.inodes[o], inumber, fd);
                    disk.write(n, block);
                    return fd;
                }
            }
        }

        // Could not find a free inode, so release our file
        // descriptor, print an error message, and finish.
        //
        fileTable.free(fd);
        System.err.println("Out of files");
        return -1;
    }
    
    public int open(int inumber) throws IOException {
        if(! inumberIsValid(inumber))
            return -1;
        
        // Try to get a free file descriptor.
        //
        int fd = fileTable.allocate();
        if(fd < 0)
            return -1;

        // Get the requested inode from disk.
        //
        InodeBlock inodeBlock = new InodeBlock();
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        Inode inode = inodeBlock.inodes[inumberToOffset(inumber)];

        // If the inode is allocated, associate the inode with the
        // file descriptor, and then return the fd. Otherwise, there
        // was an error so we should release the file descriptor and
        // return -1.
        //
        if(inode.flags != 0) {
            fileTable.add(inode, inumber, fd);
            return fd;
        }
        fileTable.free(fd);
        System.err.println("File " + inumber + " does not exist");
        return -1;
    }
    
    public int inumber(int fd) throws IOException {
        return fileTable.getInumber(fd);
    }
    
    public int read(int fd, byte[] buffer) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        DirectBlock block;
        int len, off = 0, limit = getReadLimit(fd, buffer.length);
        for(off = 0; off < limit; off += len) {
            block = getDirectBlock(fd, MODE.r);
            len = block.copyTo(buffer, off); // may copy some garbage in
            seek(fd, len, Whence.SEEK_CUR);
        }
        return limit;
    }
    
    public int write(int fd, byte[] buffer) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        DirectBlock block;
        int len, off = 0;
        for(off = 0; off < buffer.length; off += len) {
            if((block = getDirectBlock(fd, MODE.w)) == null) {
                System.err.println("File system is full");
                return -1;
            }
            len = block.copyFrom(buffer, off);
            seek(fd, len, Whence.SEEK_CUR);
            updateFileSize(fd);
            block.save();
        }
        return buffer.length;
    }
    
    public int seek(int fd, int offset, Whence whence) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;
        
        Inode inode = fileTable.getInode(fd);
        int ptr;
        
        switch(whence) {
            case SEEK_SET:
                ptr = offset;
                break;
            case SEEK_END:
                ptr = offset + inode.size;
                break;
            case SEEK_CUR:
                ptr = offset + fileTable.getSeekPointer(fd);
                break;
            default:
                return -1;
        }
        if(ptr < 0) {
            System.err.println("Cannot seek to offset < 0");
            return -1;
        }
        fileTable.setSeekPointer(fd, ptr);
        return ptr;
    }
    
    public int close(int fd) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        // Read the InodeBlock in, modify it, and write it back out.
        //
        InodeBlock inodeBlock = new InodeBlock();
        int inumber = fileTable.getInumber(fd);
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        inodeBlock.inodes[inumberToOffset(inumber)] = fileTable.getInode(fd);
        disk.write(inumberToBlockNum(inumber), inodeBlock);

        // Free the file descriptor and return successfully.
        //
        fileTable.free(fd);
        return 0;
    }
    
    public int delete(int inumber) throws IOException {
        // Disallow deleting of open files.
        //
        int fd;
        if((fd = fileTable.getFdFromInumber(inumber)) != -1) {
            System.err.println("Cannot delete open file (fd = " + fd + ")");
            return -1;
        }

        // Get inode for this file.
        //
        InodeBlock inodeBlock = new InodeBlock();
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        Inode inode = inodeBlock.inodes[inumberToOffset(inumber)];

        // Free all direct blocks in the free map. No need to clear
        // the inode pointers, they are cleared when allocating a new
        // file.
        //
        for(int i = 0; i < inode.ptr.length; ++i)
            if(inode.ptr[i] != 0)
                freeMap.clear(inode.ptr[i]);
        freeMap.save();

        // Mark the inode as free and write it to disk.
        //
        inode.flags = 0;
        disk.write(inumberToBlockNum(inumber), inodeBlock);
        return 0;
    }

    /**
     * Initialize the freeMap instance. Should be called at the end of
     * the constructor and from formatDisk.
     */
    private void initFreeMap() {
        freeMap = new FreeMap(disk, superBlock);
    }

    /**
     * Ensure that the fd is within the valid range and refers to an
     * open file. Prints an error message if it is invalid.
     *
     * @return boolean true if fd is valid, false otherwise
     */
    private boolean fileDescriptorIsValid(int fd) {
        if(( fd < 0                         ||
             fd >= FileTable.MAX_FILES      ||
             fileTable.getInode(fd) == null )) {
            System.err.println("File descriptor " + fd + " is invalid");
            return false;
        }
        return true;
    }

    /**
     * Ensure that the inumber is within the valid range. Prints an
     * error message if it is invalid.
     *
     * @return boolean true if inumber is valid, false otherwise
     */
    private boolean inumberIsValid(int inumber) {
        if(inumber <= 0 || inumber >= superBlock.isize * InodeBlock.COUNT) {
            System.err.println("inumber " + inumber + " is invalid");
            return false;
        }
        return true;
    }

    /**
     * Get a DirectBlock object representing the direct block given
     * the current seek position in the open file identified by fd. A
     * DirectBlock references the direct block and offset within that
     * block containing the current seek position.
     *
     * If the current seek position is within a hole or beyond the end
     * of a file, then if create is true then a block will be
     * allocated to fill the hole. If there is no more free space in
     * the file system, null will be returned. If the seek position is
     * in a hole and create is false, then a block containing zeroes
     * will be returned.
     *
     * @param   fd          valid file descriptor of an open file
     * @param   mode        MODE.w if holes should be filled, MODE.r
     *                      otherwise (holes will be read as blocks
     *                      of all zeros)
     * @returns DirectBlock block and offset in that block where the
     *                      seek position of fd can be found
     */
    private DirectBlock getDirectBlock(int fd, MODE mode) { //FIXME!!!
    	Inode inode   = fileTable.getInode(fd);
        int seekPtr   = fileTable.getSeekPointer(fd);        
        // The blockNum is a logical block number referring to a
        // pointer in the inode.
        int blockNum  = seekPtr / Disk.BLOCK_SIZE;
        int blockOff  = seekPtr % Disk.BLOCK_SIZE;

        if(blockNum < 10) {
        	return newDataBlock(mode, inode, blockNum, blockOff);
//        	boolean fresh = inode.ptr[blockNum] == 0;
//            if(fresh)
//                if(mode == MODE.r)
//                    return DirectBlock.hole;
//                else if((inode.ptr[blockNum] = freeMap.find()) == 0)
//                    return null;
//            return new DirectBlock(disk, inode.ptr[blockNum], blockOff, fresh);
        }
        else {
        	return getIndirectBlock(blockNum, blockOff, inode, mode);
        }
    }
    
    /**
     * Get new Block at a disk position for storing data.
     * @param	diskPointer pointer that indicates where's indiBlock.
     * @param   indiBlock   stores pointers that points next level's
     * 						blocks.
     * @param   index       pointer that points where the the new 
     * 						data's pointer should be stored.
     * @returns DirectBlock block and offset in that block where the
     *                      seek position of fd can be found
     */
    private DirectBlock newDataBlock(MODE mode, int diskPointer, IndirectBlock indiBlock, int index, int blockOff){
    	boolean fresh = indiBlock.ptr[index] == 0;
        if(fresh)
	        if(mode == MODE.r)
	            return DirectBlock.hole;
	        else if((indiBlock.ptr[index] = freeMap.find()) == 0) 
	        	return null;
        disk.write(diskPointer, indiBlock);
        return new DirectBlock(disk, indiBlock.ptr[index], blockOff, fresh);
    }
    
    /**
     * Get new Block at a disk position for storing data.
     * @param	diskPointer pointer that indicates where's indiBlock.
     * @param   inode   	stores pointers that points data blocks.
     * @param   blockNum    points where the the new data's pointer 
     * 						should be stored.
     * @returns DirectBlock block and offset in that block where the
     *                      seek position of fd can be found
     */
    private DirectBlock newDataBlock(MODE mode, Inode inode, int blockNum, int blockOff){
    	boolean fresh = inode.ptr[blockNum] == 0;
        if(fresh)
            if(mode == MODE.r)
                return DirectBlock.hole;
            else if((inode.ptr[blockNum] = freeMap.find()) == 0)
                return null;
        return new DirectBlock(disk, inode.ptr[blockNum], blockOff, fresh);
    }
    
    /**
     * Get new Block at a disk position for storing data.
     * @param	currentBlock the block that stores pointers that 
     * 						points the blocks we want to find
     * @param   ptrIndex   	a position indicates a pointer that 
     * 						points where's the next block
     * @param   nextBlock   next level of page block
     * @param   preBlcokPtr	a position where the currentBlock stores
     * 						at the previous level block
     * @returns boolean		indicates whether a page block is found
     */
    private boolean getPageBlock(IndirectBlock currentBlock, int ptrIndex, IndirectBlock nextBlock, int preBlcokPtr){
    	if(currentBlock.ptr[ptrIndex] == 0) {
    		if((currentBlock.ptr[ptrIndex] = freeMap.find()) == 0) return false;
    		disk.write(preBlcokPtr, currentBlock);
    	}
    	else disk.read(currentBlock.ptr[ptrIndex], nextBlock);
    	return true;
    }
    
    /**
     * Get new Block at a disk position for storing data.
     * @param	currentBlock the block that stores pointers that 
     * 						points the blocks we want to find
     * @param   ptrIndex   	a position indicates a pointer that 
     * 						points where's the next block
     * @param   nextBlock   next level of page block
     * @returns boolean		indicates whether a page block is found
     */
    private boolean getPageBlock(Inode currentBlock, int ptrIndex, IndirectBlock nextBlock){
    	if(currentBlock.ptr[ptrIndex] == 0) {
    		if((currentBlock.ptr[ptrIndex] = freeMap.find()) == 0) return true;
    	}
    	else disk.read(currentBlock.ptr[ptrIndex], nextBlock);
    	return true;
    }
    
    /**
     * Get a dataBlock indirectly.
     * @param   blockNum    block number of the seeking block
     * @param   mode        MODE.w if holes should be filled, MODE.r
     *                      otherwise (holes will be read as blocks
     *                      of all zeros)
     * @returns DirectBlock block and offset in that block where the
     *                      seek position of fd can be found
     */
    private DirectBlock getIndirectBlock(int blockNum, int blockOff, Inode inode, MODE mode){
    	IndirectBlock firstTempBlock = new IndirectBlock();
    	IndirectBlock secondTempBlock = new IndirectBlock();
    	IndirectBlock thirdTempBlock = new IndirectBlock();
    	
    	int singleIndirectBound = DirectBlock.COUNT + IndirectBlock.COUNT;
    	double doubleIndirectBound = singleIndirectBound + Math.pow(IndirectBlock.COUNT, 2);
    	double tripleIndirectBound = doubleIndirectBound + Math.pow(IndirectBlock.COUNT, 3);
    	
        if(blockNum >= tripleIndirectBound){
            System.err.println("File too large. Unsupport.");
            System.exit(1);
        }
        
        if(blockNum < singleIndirectBound){
        	
        	int index = blockNum - DirectBlock.COUNT;     	
        	if( getPageBlock(inode, 10, firstTempBlock) == false) return null;
        	return newDataBlock(mode, inode.ptr[10], firstTempBlock, index, blockOff);

        } else if(blockNum < doubleIndirectBound){
        	
        	int index = blockNum - singleIndirectBound;
        	int firstIndex = index / 128;
        	int secondIndex = index % 128;
        	if( (getPageBlock(inode, 11, firstTempBlock) == false) ||
        		(getPageBlock(firstTempBlock, firstIndex, secondTempBlock, inode.ptr[11]) == false)
        		)return null;
        	return newDataBlock(mode, firstTempBlock.ptr[firstIndex], secondTempBlock, secondIndex, blockOff);

        } else {
        	
        	double index = blockNum - doubleIndirectBound;
        	int firstIndex = (int) (index / (128 * 128));
        	int secondIndex = (int) (index / 128);
        	int thirdIndex = (int) (index % 128);
        	if( (getPageBlock(inode, 12, firstTempBlock) == false) ||
        		(getPageBlock(firstTempBlock, firstIndex, secondTempBlock, inode.ptr[12]) == false) ||
        		(getPageBlock(secondTempBlock, secondIndex, thirdTempBlock, firstTempBlock.ptr[firstIndex]) == false)
        	) return null;
        	return newDataBlock(mode, secondTempBlock.ptr[secondIndex], thirdTempBlock, thirdIndex, blockOff);
        } 
    }


    
    /**
     * Convert an inumber to the number of the InodeBlock that
     * contains it.
     *
     * @param inumber inumber of inode to locate
     * @return int block number of InodeBlock
     */
    private int inumberToBlockNum(int inumber) {
        return superBlock.iblock0() + (inumber - 1) / InodeBlock.COUNT;
    }

    /**
     * Convert an inumber to its offset within its InodeBlock.
     *
     * @param inumber inumber of the inode to locate
     * @return int offset of inode within its InodeBlock
     */
    private int inumberToOffset(int inumber) {
        return (inumber - 1) % InodeBlock.COUNT;
    }

    /**
     * Update the size of a file if needed so that it is always at
     * least as large as the current seek pointer.
     */
    private void updateFileSize(int fd) {
        int currentSize = fileTable.getInode(fd).size;
        int seekPointer = fileTable.getSeekPointer(fd);
        if(seekPointer > currentSize)
            fileTable.setFileSize(fd, seekPointer);
    }

    /**
     * Get the maximum number of bytes that can be read from open file
     * fd into a buffer of length len. If the seek pointer is beyond
     * the end of the file, always returns 0 (since nothing can be
     * read beyond the end of a file).
     */
    private int getReadLimit(int fd, int len) {
        int rest = fileTable.getInode(fd).size - fileTable.getSeekPointer(fd);
        return Math.max(0, Math.min(len, rest));
    }
}
